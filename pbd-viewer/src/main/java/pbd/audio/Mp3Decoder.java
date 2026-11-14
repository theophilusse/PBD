package pbd.audio;

import java.io.IOException;
import java.util.Arrays;

/**
 * MPEG-1 / MPEG-2 / MPEG-2.5 Audio Layer III ("MP3") decoder in pure Java - no dependency, no native code.
 *
 * <p>WHY IT EXISTS: {@code javax.sound.sampled} has no MP3 decoder, and the project wants {@code item-equip.mp3} to play
 * (see {@link SoundPlayer}). Pulling a third-party decoder jar in would change the build, which this project's
 * layout forbids touching; a self-contained class does not.
 *
 * <p>ORIGIN: a port of minimp3 (https://github.com/lieff/minimp3 - released under CC0, i.e. public domain), Layer III only. Its
 * data tables are NOT retyped: {@link Mp3Tables} is generated from the C source by tools/scripts/gen_mp3_tables.py.
 * The port keeps minimp3's structure and the order of its floating-point operations, so that its output can be compared
 * with the C decoder bit for bit (tools/regression/RegressionMp3Decoder pins that on fixtures; the ISO/IEC 11172-4
 * conformance streams were run against it once, by hand - see docs/TEST_PROCEDURE.md for what was and was not run).
 *
 * <p>WHAT DIFFERS FROM minimp3 (each one is pinned by a test):
 * <ul>
 *   <li>No SIMD paths - they compute the same numbers as the scalar code that is ported.</li>
 *   <li>Layer I and II are refused with a clear message (an {@code .mp3} that old is not expected).</li>
 *   <li>The 16-bit rounding is "half away from zero" for every sample. minimp3's own rounding sends the range
 *       (-1.5, -0.5] to 0 - an error of 1 LSB on quiet negative samples.</li>
 *   <li>The "private bits" of the side information are ignored. minimp3 reads them as scale-factor-sharing flags of
 *       the first granule, which would corrupt a stream whose encoder sets them.</li>
 *   <li>Intensity stereo only exists in joint-stereo mode; the header bit alone does not switch it on.</li>
 *   <li>The intensity-stereo positions of a frame start at zero. minimp3 leaves in them what the
 *       previous call wrote, so a frame whose two channels differ in block type decodes differently depending on its neighbours.</li>
 *   <li>Mixed blocks at 8 kHz (MPEG-2.5) are refused: minimp3 writes 24 lines past the channel for them.</li>
 *   <li>Corrupt data never loops or reads out of range: a frame that cannot be decoded is skipped
 *       (and the file-level {@link #decode(byte[])} throws {@link FormatException} when nothing at all can be decoded).</li>
 *   <li>The whole-file entry point skips ID3v2 / ID3v1 tags, does not play the Xing/Info/VBRI metadata frame, and
 *       trims the encoder delay and padding that a LAME tag announces (gapless, like ffmpeg does).</li>
 * </ul>
 *
 * <p>The decoded float samples are in 16-bit units (full scale is +-32768), exactly what minimp3 computes before
 * its rounding. An instance keeps the decoder state between frames and is not thread safe; the static
 * {@link #decode(byte[])} creates its own.
 */
public final class Mp3Decoder {

    /** The file is not an MP3 this decoder can play (no frame, unsupported layer, format change, too long...). */
    public static final class FormatException extends IOException {
        private static final long serialVersionUID = 1L;

        public FormatException(String message) {
            super(message);
        }
    }

    /** Result of {@link #decode(byte[])}: 16-bit signed PCM, channels interleaved. */
    public static final class Pcm {
        public final int sampleRate;
        public final int channels;
        public final short[] samples;

        public Pcm(int sampleRate, int channels, short[] samples) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.samples = samples;
        }

        /** Sample frames (one sample per channel). */
        public int frames() {
            return samples.length / channels;
        }

        public double seconds() {
            return frames() / (double) sampleRate;
        }

        /** The samples as the bytes of a little-endian 16-bit PCM stream (what a javax.sound.sampled Clip is opened with). */
        public byte[] toLittleEndianBytes() {
            byte[] out = new byte[samples.length * 2];
            for (int i = 0; i < samples.length; i++) {
                out[2 * i] = (byte) samples[i];
                out[2 * i + 1] = (byte) (samples[i] >> 8);
            }
            return out;
        }
    }

    /** What {@link #decodeFrame} tells about the frame it consumed. */
    public static final class FrameInfo {
        /** Bytes to advance by: the skipped junk before the frame plus the frame itself (0 = nothing usable left). */
        public int frameBytes;
        /** Junk bytes skipped in front of the frame. */
        public int frameOffset;
        public int channels;
        public int hz;
        /** 1, 2 or 3 (only 3 is decoded). */
        public int layer;
        public int bitrateKbps;
    }

    /** Largest number of samples one frame can produce (1152 per channel, two channels). */
    public static final int MAX_SAMPLES_PER_FRAME = 1152 * 2;
    /** Default cap of {@link #decode(byte[])}: a sound effect, not a song. */
    public static final int DEFAULT_MAX_SECONDS = 120;

    private static final int MAX_FREE_FORMAT_FRAME_SIZE = 2304;
    private static final int MAX_FRAME_SYNC_MATCHES = 10;
    private static final int MAX_L3_FRAME_PAYLOAD_BYTES = MAX_FREE_FORMAT_FRAME_SIZE;
    private static final int MAX_BITRESERVOIR_BYTES = 511;
    /**
     * minimp3 reads a few bytes beyond the end of the main data while it decodes the last Huffman codes, and a corrupt frame
     * can make the big-values loop run on for ~1.7 KB of garbage before it is noticed; the buffer is padded with zeros so that
     * what is read there is defined and in range.
     */
    private static final int MAIN_DATA_SLACK = 2048 + 16;
    private static final int SHORT_BLOCK_TYPE = 2;
    private static final int STOP_BLOCK_TYPE = 3;
    private static final int HDR_SIZE = 4;
    private static final int BITS_DEQUANTIZER_OUT = -1;
    private static final int MAX_SCF = 255 + BITS_DEQUANTIZER_OUT * 4 - 210;
    private static final int MAX_SCFI = (MAX_SCF + 3) & ~3;

    // ---------------------------------------------------------------- header fields

    private static int b(byte[] h, int o, int i) {
        return h[o + i] & 0xFF;
    }

    private static boolean isMono(byte[] h, int o) {
        return (b(h, o, 3) & 0xC0) == 0xC0;
    }

    private static boolean isMsStereo(byte[] h, int o) {
        return (b(h, o, 3) & 0xE0) == 0x60;
    }

    private static boolean isFreeFormat(byte[] h, int o) {
        return (b(h, o, 2) & 0xF0) == 0;
    }

    private static boolean isCrc(byte[] h, int o) {
        return (b(h, o, 1) & 1) == 0;
    }

    private static boolean testPadding(byte[] h, int o) {
        return (b(h, o, 2) & 0x2) != 0;
    }

    private static boolean testMpeg1(byte[] h, int o) {
        return (b(h, o, 1) & 0x8) != 0;
    }

    private static boolean testNotMpeg25(byte[] h, int o) {
        return (b(h, o, 1) & 0x10) != 0;
    }

    /** Mode extension "intensity stereo": only meaningful in joint-stereo mode (minimp3 tests the bit alone). */
    private static boolean testIStereo(byte[] h, int o) {
        return (b(h, o, 3) & 0xD0) == 0x50;
    }

    private static boolean testMsStereo(byte[] h, int o) {
        return (b(h, o, 3) & 0x20) != 0;
    }

    private static int layerBits(byte[] h, int o) {
        return (b(h, o, 1) >> 1) & 3;
    }

    private static int bitrateIndex(byte[] h, int o) {
        return b(h, o, 2) >> 4;
    }

    private static int sampleRateIndex(byte[] h, int o) {
        return (b(h, o, 2) >> 2) & 3;
    }

    /** 0..2 = MPEG-2.5, 3..5 = MPEG-2, 6..8 = MPEG-1. */
    private static int mySampleRate(byte[] h, int o) {
        int h1 = b(h, o, 1);
        return sampleRateIndex(h, o) + (((h1 >> 3) & 1) + ((h1 >> 4) & 1)) * 3;
    }

    private static boolean isFrame576(byte[] h, int o) {
        return (b(h, o, 1) & 14) == 2;
    }

    private static boolean isLayer1(byte[] h, int o) {
        return (b(h, o, 1) & 6) == 6;
    }

    private static boolean hdrValid(byte[] h, int o) {
        int h1 = b(h, o, 1);
        return b(h, o, 0) == 0xFF
            && ((h1 & 0xF0) == 0xF0 || (h1 & 0xFE) == 0xE2)
            && layerBits(h, o) != 0
            && bitrateIndex(h, o) != 15
            && sampleRateIndex(h, o) != 3;
    }

    private static boolean hdrCompare(byte[] h1, int o1, byte[] h2, int o2) {
        return hdrValid(h2, o2)
            && ((b(h1, o1, 1) ^ b(h2, o2, 1)) & 0xFE) == 0
            && ((b(h1, o1, 2) ^ b(h2, o2, 2)) & 0x0C) == 0
            && isFreeFormat(h1, o1) == isFreeFormat(h2, o2);
    }

    private static int hdrBitrateKbps(byte[] h, int o) {
        return 2 * Mp3Tables.HALFRATE[testMpeg1(h, o) ? 1 : 0][layerBits(h, o) - 1][bitrateIndex(h, o)];
    }

    private static int hdrSampleRateHz(byte[] h, int o) {
        return Mp3Tables.HZ[sampleRateIndex(h, o)] >> (testMpeg1(h, o) ? 0 : 1) >> (testNotMpeg25(h, o) ? 0 : 1);
    }

    private static int hdrFrameSamples(byte[] h, int o) {
        return isLayer1(h, o) ? 384 : (1152 >> (isFrame576(h, o) ? 1 : 0));
    }

    private static int hdrFrameBytes(byte[] h, int o, int freeFormatSize) {
        int frameBytes = hdrFrameSamples(h, o) * hdrBitrateKbps(h, o) * 125 / hdrSampleRateHz(h, o);
        if (isLayer1(h, o)) {
            frameBytes &= ~3; // slot align
        }
        return frameBytes != 0 ? frameBytes : freeFormatSize;
    }

    private static int hdrPadding(byte[] h, int o) {
        return testPadding(h, o) ? (isLayer1(h, o) ? 4 : 1) : 0;
    }

    // ---------------------------------------------------------------- bit reader

    /** minimp3's bs_t: a bit position in a byte array, with the position past which reads return 0. */
    private static final class Bits {
        byte[] buf;
        int base;
        int pos;
        int limit;

        void init(byte[] data, int offset, int bytes) {
            buf = data;
            base = offset;
            pos = 0;
            limit = bytes * 8;
        }

        int get(int n) {
            int s = pos & 7;
            int shl = n + s;
            int p = base + (pos >> 3);
            if ((pos += n) > limit) {
                return 0;
            }
            int next = (buf[p++] & 0xFF) & (255 >> s);
            int cache = 0;
            while ((shl -= 8) > 0) {
                cache |= next << shl;
                next = buf[p++] & 0xFF;
            }
            return cache | (next >>> -shl);
        }
    }

    /** A frame that cannot be decoded; thrown inside the decoder and turned into "skip this frame". */
    private static final class Corrupt extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Corrupt() {
            super(null, null, false, false);
        }
    }

    /** minimp3's L3_gr_info_t: the side information of one granule of one channel. */
    private static final class Gr {
        int[] sfbTab;
        int part23Length, bigValues, scalefacCompress;
        int globalGain, blockType, mixedBlockFlag, nLongSfb, nShortSfb;
        final int[] tableSelect = new int[3];
        final int[] regionCount = new int[3];
        final int[] subblockGain = new int[3];
        int preflag, scalefacScale, count1Table, scfsi;
    }

    // ---------------------------------------------------------------- decoder state

    private final float[] mdctOverlap = new float[2 * 9 * 32];
    private final float[] qmfState = new float[15 * 2 * 32];
    private int reserv;
    private int freeFormatBytes;
    private final byte[] header = new byte[HDR_SIZE];
    private final byte[] reservBuf = new byte[MAX_BITRESERVOIR_BYTES];

    // the scratch of one frame (minimp3's mp3dec_scratch_t)
    private final Bits bs = new Bits();
    private final byte[] maindata = new byte[MAX_BITRESERVOIR_BYTES + MAX_L3_FRAME_PAYLOAD_BYTES + MAIN_DATA_SLACK];
    private final Gr[] grInfo = {new Gr(), new Gr(), new Gr(), new Gr()};
    private final float[] grbuf = new float[2 * 576];
    private final float[] scf = new float[40];
    private final float[] syn = new float[(18 + 15) * 2 * 32];
    private final int[][] istPos = new int[2][39];
    private final int[] iscf = new int[40];
    private final int[] scfSize = new int[4];
    private int foundFrameBytes; // second result of findFrame

    public Mp3Decoder() {
    }

    /** Forget everything between frames (minimp3's memset of the decoder at a re-synchronisation). */
    private void reset() {
        Arrays.fill(mdctOverlap, 0f);
        Arrays.fill(qmfState, 0f);
        reserv = 0;
        freeFormatBytes = 0;
        Arrays.fill(header, (byte) 0);
        Arrays.fill(reservBuf, (byte) 0);
    }

    // ---------------------------------------------------------------- frame synchronisation

    private static boolean matchFrame(byte[] hdr, int ho, int mp3Bytes, int frameBytes) {
        int i = 0;
        for (int nmatch = 0; nmatch < MAX_FRAME_SYNC_MATCHES; nmatch++) {
            i += hdrFrameBytes(hdr, ho + i, frameBytes) + hdrPadding(hdr, ho + i);
            if (i + HDR_SIZE > mp3Bytes) {
                return nmatch > 0;
            }
            if (!hdrCompare(hdr, ho, hdr, ho + i)) {
                return false;
            }
        }
        return true;
    }

    /** Index (relative to {@code off}) of the first frame, or {@code mp3Bytes} when there is none; sets foundFrameBytes. */
    private int findFrame(byte[] mp3, int off, int mp3Bytes) {
        for (int i = 0; i < mp3Bytes - HDR_SIZE; i++) {
            int o = off + i;
            if (hdrValid(mp3, o)) {
                int frameBytes = hdrFrameBytes(mp3, o, freeFormatBytes);
                int frameAndPadding = frameBytes + hdrPadding(mp3, o);
                for (int k = HDR_SIZE; frameBytes == 0 && k < MAX_FREE_FORMAT_FRAME_SIZE && i + 2 * k < mp3Bytes - HDR_SIZE; k++) {
                    if (hdrCompare(mp3, o, mp3, o + k)) {
                        int fb = k - hdrPadding(mp3, o);
                        int nextfb = fb + hdrPadding(mp3, o + k);
                        if (i + k + nextfb + HDR_SIZE > mp3Bytes || !hdrCompare(mp3, o, mp3, o + k + nextfb)) {
                            continue;
                        }
                        frameAndPadding = k;
                        frameBytes = fb;
                        freeFormatBytes = fb;
                    }
                }
                if ((frameBytes != 0 && i + frameAndPadding <= mp3Bytes && matchFrame(mp3, o, mp3Bytes - i, frameBytes))
                    || (i == 0 && frameAndPadding == mp3Bytes)) {
                    foundFrameBytes = frameAndPadding;
                    return i;
                }
                freeFormatBytes = 0;
            }
        }
        foundFrameBytes = 0;
        return mp3Bytes;
    }

    // ---------------------------------------------------------------- layer III: side information

    /** Reads the side information into grInfo; returns main_data_begin, or -1 for a frame that cannot be right. */
    private int readSideInfo(Bits bs, byte[] hdr, int ho) {
        int tables;
        int scfsi = 0;
        int mainDataBegin;
        int part23Sum = 0;
        int srIdx = mySampleRate(hdr, ho);
        srIdx -= srIdx != 0 ? 1 : 0;
        boolean mono = isMono(hdr, ho);
        boolean mpeg1 = testMpeg1(hdr, ho);
        int grCount = mono ? 1 : 2;

        if (mpeg1) {
            grCount *= 2;
            mainDataBegin = bs.get(9);
            scfsi = bs.get(7 + grCount);
        } else {
            mainDataBegin = bs.get(8 + grCount) >> grCount;
        }
        int idx = 0;
        do {
            Gr gr = grInfo[idx];
            if (mono) {
                scfsi <<= 4;
            }
            gr.part23Length = bs.get(12);
            part23Sum += gr.part23Length;
            gr.bigValues = bs.get(9);
            if (gr.bigValues > 288) {
                return -1;
            }
            gr.globalGain = bs.get(8);
            gr.scalefacCompress = bs.get(mpeg1 ? 4 : 9);
            gr.sfbTab = Mp3Tables.SCF_LONG[srIdx];
            gr.nLongSfb = 22;
            gr.nShortSfb = 0;
            if (bs.get(1) != 0) {
                gr.blockType = bs.get(2);
                if (gr.blockType == 0) {
                    return -1;
                }
                gr.mixedBlockFlag = bs.get(1);
                gr.regionCount[0] = 7;
                gr.regionCount[1] = 255;
                if (gr.blockType == SHORT_BLOCK_TYPE) {
                    scfsi &= 0x0F0F;
                    if (gr.mixedBlockFlag == 0) {
                        gr.regionCount[0] = 8;
                        gr.sfbTab = Mp3Tables.SCF_SHORT[srIdx];
                        gr.nLongSfb = 0;
                        gr.nShortSfb = 39;
                    } else {
                        gr.sfbTab = Mp3Tables.SCF_MIXED[srIdx];
                        gr.nLongSfb = mpeg1 ? 8 : 6;
                        gr.nShortSfb = 30;
                    }
                }
                tables = bs.get(10);
                tables <<= 5;
                gr.subblockGain[0] = bs.get(3);
                gr.subblockGain[1] = bs.get(3);
                gr.subblockGain[2] = bs.get(3);
            } else {
                gr.blockType = 0;
                gr.mixedBlockFlag = 0;
                tables = bs.get(15);
                gr.regionCount[0] = bs.get(4);
                gr.regionCount[1] = bs.get(3);
                gr.regionCount[2] = 255;
            }
            gr.tableSelect[0] = tables >> 10;
            gr.tableSelect[1] = (tables >> 5) & 31;
            gr.tableSelect[2] = tables & 31;
            gr.preflag = mpeg1 ? bs.get(1) : (gr.scalefacCompress >= 500 ? 1 : 0);
            gr.scalefacScale = bs.get(1);
            gr.count1Table = bs.get(1);
            // The scale-factor-sharing flags (scfsi) only exist for the second granule. minimp3 leaves the private bits
            // of the side information in the slot of the first one; they are zero for most encoders, not for all.
            boolean firstGranule = idx < (mono ? 1 : 2);
            gr.scfsi = (!mpeg1 || firstGranule) ? 0 : ((scfsi >> 12) & 15);
            scfsi <<= 4;
            idx++;
        } while (--grCount != 0);

        if (part23Sum + bs.pos > bs.limit + mainDataBegin * 8) {
            return -1;
        }
        return mainDataBegin;
    }

    // ---------------------------------------------------------------- layer III: scale factors

    private void readScalefactors(int[] iscfOut, int[] ist, int[] sizes, int[] counts, int countsOff, Bits bitbuf, int scfsi) {
        int p = 0; // position in iscfOut and ist
        for (int i = 0; i < 4 && counts[countsOff + i] != 0; i++, scfsi *= 2) {
            int cnt = counts[countsOff + i];
            if ((scfsi & 8) != 0) {
                for (int k = 0; k < cnt; k++) {
                    iscfOut[p + k] = ist[p + k];
                }
            } else {
                int bits = sizes[i];
                if (bits == 0) {
                    for (int k = 0; k < cnt; k++) {
                        iscfOut[p + k] = 0;
                        ist[p + k] = 0;
                    }
                } else {
                    int maxScf = (scfsi < 0) ? (1 << bits) - 1 : -1;
                    for (int k = 0; k < cnt; k++) {
                        int s = bitbuf.get(bits);
                        ist[p + k] = (s == maxScf) ? 255 : s; // 255 = minimp3's (uint8_t)-1: "illegal intensity position"
                        iscfOut[p + k] = s;
                    }
                }
            }
            p += cnt;
        }
        iscfOut[p] = 0;
        iscfOut[p + 1] = 0;
        iscfOut[p + 2] = 0;
    }

    private static float ldexpQ2(float y, int expQ2) {
        int e;
        do {
            e = Math.min(30 * 4, expQ2);
            y *= Mp3Tables.EXPFRAC[e & 3] * (1 << 30 >> (e >> 2));
        } while ((expQ2 -= e) > 0);
        return y;
    }

    private void decodeScalefactors(byte[] hdr, int ho, int[] ist, Bits bs, Gr gr, float[] scfOut, int ch) {
        int[] partition = Mp3Tables.SCF_PARTITIONS[(gr.nShortSfb != 0 ? 1 : 0) + (gr.nLongSfb == 0 ? 1 : 0)];
        int partOff = 0;
        int scfShift = gr.scalefacScale + 1;
        int scfsi = gr.scfsi;
        Arrays.fill(iscf, 0);

        if (testMpeg1(hdr, ho)) {
            int part = Mp3Tables.SCFC_DECODE[gr.scalefacCompress];
            scfSize[1] = scfSize[0] = part >> 2;
            scfSize[3] = scfSize[2] = part & 3;
        } else {
            int ist1 = testIStereo(hdr, ho) && ch != 0 ? 1 : 0;
            int sfc = gr.scalefacCompress >> ist1;
            int k = ist1 * 3 * 4;
            int modprod;
            for (; sfc >= 0; sfc -= modprod, k += 4) {
                modprod = 1;
                for (int i = 3; i >= 0; i--) {
                    scfSize[i] = sfc / modprod % Mp3Tables.MOD[k + i];
                    modprod *= Mp3Tables.MOD[k + i];
                }
            }
            partOff += k;
            scfsi = -16;
        }
        readScalefactors(iscf, ist, scfSize, partition, partOff, bs, scfsi);

        if (gr.nShortSfb != 0) {
            int sh = 3 - scfShift;
            for (int i = 0; i < gr.nShortSfb; i += 3) {
                iscf[gr.nLongSfb + i + 0] += gr.subblockGain[0] << sh;
                iscf[gr.nLongSfb + i + 1] += gr.subblockGain[1] << sh;
                iscf[gr.nLongSfb + i + 2] += gr.subblockGain[2] << sh;
            }
        } else if (gr.preflag != 0) {
            for (int i = 0; i < 10; i++) {
                iscf[11 + i] += Mp3Tables.PREAMP[i];
            }
        }

        int gainExp = gr.globalGain + BITS_DEQUANTIZER_OUT * 4 - 210 - (isMsStereo(hdr, ho) ? 2 : 0);
        float gain = ldexpQ2(1 << (MAX_SCFI / 4), MAX_SCFI - gainExp);
        for (int i = 0; i < gr.nLongSfb + gr.nShortSfb; i++) {
            scfOut[i] = ldexpQ2(gain, iscf[i] << scfShift);
        }
    }

    // ---------------------------------------------------------------- layer III: Huffman decoding and requantisation

    private static float pow43(int x) {
        float frac;
        int sign;
        int mult = 256;

        if (x < 129) {
            return Mp3Tables.POW43[16 + x];
        }
        if (x < 1024) {
            mult = 16;
            x <<= 3;
        }
        sign = 2 * x & 64;
        frac = (float) ((x & 63) - sign) / ((x & ~63) + sign);
        return Mp3Tables.POW43[16 + ((x + sign) >> 6)] * (1.f + frac * ((4.f / 3) + frac * (2.f / 9))) * mult;
    }

    /**
     * Decodes the Huffman-coded spectrum of one granule of one channel into dst[d..d+575], requantised.
     * The bit cache of minimp3 (a 32-bit window over the stream, refilled a byte at a time) is kept in locals.
     */
    private static void huffman(float[] dst, int d, Bits bs, Gr gr, float[] scfIn, int layer3grLimit) {
        final short[] tabs = Mp3Tables.TABS;
        final int[] sfb = gr.sfbTab;
        final byte[] buf = bs.buf;
        int sfbI = 0;
        int scfI = 0;
        float one = 0.0f;
        int ireg = 0;
        int bigValCnt = gr.bigValues;
        int next = bs.base + (bs.pos >> 3);
        int cache = (((buf[next] & 0xFF) << 24) | ((buf[next + 1] & 0xFF) << 16) | ((buf[next + 2] & 0xFF) << 8) | (buf[next + 3] & 0xFF))
            << (bs.pos & 7);
        int sh = (bs.pos & 7) - 8;
        next += 4;
        int np;

        // Termination and bounds: big_values <= 288 pairs is checked with the side information, every band table covers exactly
        // 576 lines (= 288 pairs; checked by RegressionMp3Decoder), and the last region's band count is 255: so the bands run
        // out only after the pairs do, and there are never more than three regions.
        while (bigValCnt > 0) {
            int tabNum = gr.tableSelect[ireg];
            int sfbCnt = gr.regionCount[ireg++];
            int codebook = Mp3Tables.TABINDEX[tabNum];
            int linbits = Mp3Tables.LINBITS[tabNum];
            do {
                np = sfb[sfbI++] / 2;
                int pairsToDecode = Math.min(bigValCnt, np);
                one = scfIn[scfI++];
                do {
                    int w = 5;
                    int leaf = tabs[codebook + (cache >>> (32 - w))];
                    while (leaf < 0) {
                        cache <<= w;
                        sh += w;
                        w = leaf & 7;
                        leaf = tabs[codebook + (cache >>> (32 - w)) - (leaf >> 3)];
                    }
                    cache <<= leaf >> 8;
                    sh += leaf >> 8;

                    for (int j = 0; j < 2; j++, d++, leaf >>= 4) {
                        int lsb = leaf & 0x0F;
                        if (linbits != 0 && lsb == 15) {
                            lsb += cache >>> (32 - linbits);
                            cache <<= linbits;
                            sh += linbits;
                            while (sh >= 0) {
                                cache |= (buf[next++] & 0xFF) << sh;
                                sh -= 8;
                            }
                            dst[d] = one * pow43(lsb) * (cache < 0 ? -1 : 1);
                        } else {
                            dst[d] = Mp3Tables.POW43[16 + lsb - 16 * (cache >>> 31)] * one;
                        }
                        if (lsb != 0) {
                            cache <<= 1;
                            sh += 1;
                        }
                    }
                    while (sh >= 0) {
                        cache |= (buf[next++] & 0xFF) << sh;
                        sh -= 8;
                    }
                } while (--pairsToDecode != 0);
            } while ((bigValCnt -= np) > 0 && --sfbCnt >= 0);
        }

        final int[] codebookCount1 = gr.count1Table != 0 ? Mp3Tables.TAB33 : Mp3Tables.TAB32;
        for (np = 1 - bigValCnt; ; d += 4) {
            int leaf = codebookCount1[cache >>> (32 - 4)];
            if ((leaf & 8) == 0) {
                leaf = codebookCount1[(leaf >> 3) + ((cache << 4) >>> (32 - (leaf & 3)))];
            }
            cache <<= leaf & 7;
            sh += leaf & 7;
            if ((next - bs.base) * 8 - 24 + sh > layer3grLimit) {
                break;
            }
            if (--np == 0) {
                np = sfb[sfbI++] / 2;
                if (np == 0) {
                    break;
                }
                one = scfIn[scfI++];
            }
            if ((leaf & (128 >> 0)) != 0) {
                dst[d + 0] = cache < 0 ? -one : one;
                cache <<= 1;
                sh += 1;
            }
            if ((leaf & (128 >> 1)) != 0) {
                dst[d + 1] = cache < 0 ? -one : one;
                cache <<= 1;
                sh += 1;
            }
            if (--np == 0) {
                np = sfb[sfbI++] / 2;
                if (np == 0) {
                    break;
                }
                one = scfIn[scfI++];
            }
            if ((leaf & (128 >> 2)) != 0) {
                dst[d + 2] = cache < 0 ? -one : one;
                cache <<= 1;
                sh += 1;
            }
            if ((leaf & (128 >> 3)) != 0) {
                dst[d + 3] = cache < 0 ? -one : one;
                cache <<= 1;
                sh += 1;
            }
            while (sh >= 0) {
                cache |= (buf[next++] & 0xFF) << sh;
                sh -= 8;
            }
        }

        bs.pos = layer3grLimit;
    }

    // ---------------------------------------------------------------- layer III: stereo

    private static void midsideStereo(float[] g, int left, int n) {
        int right = left + 576;
        for (int i = 0; i < n; i++) {
            float a = g[left + i];
            float bb = g[right + i];
            g[left + i] = a + bb;
            g[right + i] = a - bb;
        }
    }

    private static void intensityStereoBand(float[] g, int left, int n, float kl, float kr) {
        for (int i = 0; i < n; i++) {
            g[left + i + 576] = g[left + i] * kr;
            g[left + i] = g[left + i] * kl;
        }
    }

    private static void stereoTopBand(float[] g, int right, int[] sfb, int nbands, int[] maxBand) {
        maxBand[0] = maxBand[1] = maxBand[2] = -1;
        for (int i = 0; i < nbands; i++) {
            for (int k = 0; k < sfb[i]; k += 2) {
                if (g[right + k] != 0 || g[right + k + 1] != 0) {
                    maxBand[i % 3] = i;
                    break;
                }
            }
            right += sfb[i];
        }
    }

    private static void stereoProcess(float[] g, int left, int[] ist, int[] sfb, byte[] hdr, int ho, int[] maxBand, int mpeg2Sh) {
        boolean mpeg1 = testMpeg1(hdr, ho);
        int maxPos = mpeg1 ? 7 : 64;
        for (int i = 0; sfb[i] != 0; i++) {
            int ipos = ist[i];
            if (i > maxBand[i % 3] && ipos < maxPos) {
                float kl;
                float kr;
                float s = testMsStereo(hdr, ho) ? 1.41421356f : 1;
                if (mpeg1) {
                    kl = Mp3Tables.PAN[2 * ipos];
                    kr = Mp3Tables.PAN[2 * ipos + 1];
                } else {
                    kl = 1;
                    kr = ldexpQ2(1, (ipos + 1) >> 1 << mpeg2Sh);
                    if ((ipos & 1) != 0) {
                        kl = kr;
                        kr = 1;
                    }
                }
                intensityStereoBand(g, left, sfb[i], kl * s, kr * s);
            } else if (testMsStereo(hdr, ho)) {
                midsideStereo(g, left, sfb[i]);
            }
            left += sfb[i];
        }
    }

    private void intensityStereo(float[] g, int left, int[] ist, int grBase, byte[] hdr, int ho) {
        Gr gr = grInfo[grBase];
        int[] maxBand = new int[3];
        int nSfb = gr.nLongSfb + gr.nShortSfb;
        int maxBlocks = gr.nShortSfb != 0 ? 3 : 1;

        stereoTopBand(g, left + 576, gr.sfbTab, nSfb, maxBand);
        if (gr.nLongSfb != 0) {
            maxBand[0] = maxBand[1] = maxBand[2] = Math.max(Math.max(maxBand[0], maxBand[1]), maxBand[2]);
        }
        for (int i = 0; i < maxBlocks; i++) {
            int defaultPos = testMpeg1(hdr, ho) ? 3 : 0;
            int itop = nSfb - maxBlocks + i;
            int prev = itop - maxBlocks;
            ist[itop] = maxBand[i] >= prev ? defaultPos : ist[prev];
        }
        stereoProcess(g, left, ist, gr.sfbTab, hdr, ho, maxBand, grInfo[grBase + 1].scalefacCompress & 1);
    }

    // ---------------------------------------------------------------- layer III: reordering, alias reduction, IMDCT

    /**
     * Short blocks come in window by window within each scale-factor band; this puts them in band order (minimp3's L3_reorder).
     * {@code max} is the number of lines the bands may cover. For mixed blocks at 8 kHz (MPEG-2.5) the short bands start at
     * line 72 (four long sub-bands) while the band table covers 576 lines from line 48, so they would end 24 lines past the
     * channel: minimp3 then writes into the other channel's memory, this port refuses the frame. No encoder writes mixed
     * blocks at that rate; the case is pinned by a fixture of the regression suite.
     */
    private void reorder(float[] g, int off, float[] scratch, int[] sfb, int sfbOff, int max) {
        int src = off;
        int dstI = 0;
        for (int s = sfbOff; sfb[s] != 0; s += 3) {
            int len = sfb[s];
            if (dstI + 3 * len > max) {
                throw new Corrupt();
            }
            for (int i = 0; i < len; i++, src++) {
                scratch[dstI++] = g[src + 0 * len];
                scratch[dstI++] = g[src + 1 * len];
                scratch[dstI++] = g[src + 2 * len];
            }
            src += 2 * len;
        }
        System.arraycopy(scratch, 0, g, off, dstI);
    }

    private static void antialias(float[] g, int off, int nbands) {
        final float[][] aa = Mp3Tables.AA;
        for (; nbands > 0; nbands--, off += 18) {
            for (int i = 0; i < 8; i++) {
                float u = g[off + 18 + i];
                float d = g[off + 17 - i];
                g[off + 18 + i] = u * aa[0][i] - d * aa[1][i];
                g[off + 17 - i] = u * aa[1][i] + d * aa[0][i];
            }
        }
    }

    private static void dct39(float[] y) {
        float s0, s1, s2, s3, s4, s5, s6, s7, s8, t0, t2, t4;

        s0 = y[0];
        s2 = y[2];
        s4 = y[4];
        s6 = y[6];
        s8 = y[8];
        t0 = s0 + s6 * 0.5f;
        s0 -= s6;
        t4 = (s4 + s2) * 0.93969262f;
        t2 = (s8 + s2) * 0.76604444f;
        s6 = (s4 - s8) * 0.17364818f;
        s4 += s8 - s2;

        s2 = s0 - s4 * 0.5f;
        y[4] = s4 + s0;
        s8 = t0 - t2 + s6;
        s0 = t0 - t4 + t2;
        s4 = t0 + t4 - s6;

        s1 = y[1];
        s3 = y[3];
        s5 = y[5];
        s7 = y[7];

        s3 *= 0.86602540f;
        t0 = (s5 + s1) * 0.98480775f;
        t4 = (s5 - s7) * 0.34202014f;
        t2 = (s1 + s7) * 0.64278761f;
        s1 = (s1 - s5 - s7) * 0.86602540f;

        s5 = t0 - s3 - t2;
        s7 = t4 - s3 - t0;
        s3 = t4 + s3 - t2;

        y[0] = s4 - s7;
        y[1] = s2 + s1;
        y[2] = s0 - s3;
        y[3] = s8 + s5;
        y[5] = s8 - s5;
        y[6] = s0 + s3;
        y[7] = s2 - s1;
        y[8] = s4 + s7;
    }

    private final float[] co9 = new float[9];
    private final float[] si9 = new float[9];

    private void imdct36(float[] g, int off, float[] overlap, int ovOff, float[] window, int nbands) {
        final float[] twid9 = Mp3Tables.TWID9;
        final float[] co = co9;
        final float[] si = si9;
        for (int j = 0; j < nbands; j++, off += 18, ovOff += 9) {
            co[0] = -g[off + 0];
            si[0] = g[off + 17];
            for (int i = 0; i < 4; i++) {
                si[8 - 2 * i] = g[off + 4 * i + 1] - g[off + 4 * i + 2];
                co[1 + 2 * i] = g[off + 4 * i + 1] + g[off + 4 * i + 2];
                si[7 - 2 * i] = g[off + 4 * i + 4] - g[off + 4 * i + 3];
                co[2 + 2 * i] = -(g[off + 4 * i + 3] + g[off + 4 * i + 4]);
            }
            dct39(co);
            dct39(si);

            si[1] = -si[1];
            si[3] = -si[3];
            si[5] = -si[5];
            si[7] = -si[7];

            for (int i = 0; i < 9; i++) {
                float ovl = overlap[ovOff + i];
                float sum = co[i] * twid9[9 + i] + si[i] * twid9[0 + i];
                overlap[ovOff + i] = co[i] * twid9[0 + i] - si[i] * twid9[9 + i];
                g[off + i] = ovl * window[0 + i] - sum * window[9 + i];
                g[off + 17 - i] = ovl * window[9 + i] + sum * window[0 + i];
            }
        }
    }

    private static void idct3(float x0, float x1, float x2, float[] dst) {
        float m1 = x1 * 0.86602540f;
        float a1 = x0 - x2 * 0.5f;
        dst[1] = x0 + x2;
        dst[0] = a1 + m1;
        dst[2] = a1 - m1;
    }

    private final float[] co3 = new float[3];
    private final float[] si3 = new float[3];

    /** x[xo + 3*k] are the inputs, dst[do..do+5] the outputs, overlap[oo..oo+2] the overlap (minimp3's L3_imdct12). */
    private void imdct12(float[] x, int xo, float[] dst, int dOff, float[] overlap, int oo) {
        final float[] twid3 = Mp3Tables.TWID3;
        idct3(-x[xo + 0], x[xo + 6] + x[xo + 3], x[xo + 12] + x[xo + 9], co3);
        idct3(x[xo + 15], x[xo + 12] - x[xo + 9], x[xo + 6] - x[xo + 3], si3);
        si3[1] = -si3[1];

        for (int i = 0; i < 3; i++) {
            float ovl = overlap[oo + i];
            float sum = co3[i] * twid3[3 + i] + si3[i] * twid3[0 + i];
            overlap[oo + i] = co3[i] * twid3[0 + i] - si3[i] * twid3[3 + i];
            dst[dOff + i] = ovl * twid3[2 - i] - sum * twid3[5 - i];
            dst[dOff + 5 - i] = ovl * twid3[5 - i] + sum * twid3[2 - i];
        }
    }

    private final float[] tmp18 = new float[18];

    private void imdctShort(float[] g, int off, float[] overlap, int ovOff, int nbands) {
        for (; nbands > 0; nbands--, ovOff += 9, off += 18) {
            System.arraycopy(g, off, tmp18, 0, 18);
            System.arraycopy(overlap, ovOff, g, off, 6);
            imdct12(tmp18, 0, g, off + 6, overlap, ovOff + 6);
            imdct12(tmp18, 1, g, off + 12, overlap, ovOff + 6);
            imdct12(tmp18, 2, overlap, ovOff, overlap, ovOff + 6);
        }
    }

    private static void changeSign(float[] g, int off) {
        off += 18;
        for (int bnd = 0; bnd < 32; bnd += 2, off += 36) {
            for (int i = 1; i < 18; i += 2) {
                g[off + i] = -g[off + i];
            }
        }
    }

    private void imdctGr(float[] g, int off, float[] overlap, int ovOff, int blockType, int nLongBands) {
        if (nLongBands != 0) {
            imdct36(g, off, overlap, ovOff, Mp3Tables.MDCT_WINDOW[0], nLongBands);
            off += 18 * nLongBands;
            ovOff += 9 * nLongBands;
        }
        if (blockType == SHORT_BLOCK_TYPE) {
            imdctShort(g, off, overlap, ovOff, 32 - nLongBands);
        } else {
            imdct36(g, off, overlap, ovOff, Mp3Tables.MDCT_WINDOW[blockType == STOP_BLOCK_TYPE ? 1 : 0], 32 - nLongBands);
        }
    }

    // ---------------------------------------------------------------- layer III: one granule, the bit reservoir

    private void saveReservoir() {
        int pos = (bs.pos + 7) / 8;
        int remains = bs.limit / 8 - pos;
        if (remains > MAX_BITRESERVOIR_BYTES) {
            pos += remains - MAX_BITRESERVOIR_BYTES;
            remains = MAX_BITRESERVOIR_BYTES;
        }
        if (remains > 0) {
            System.arraycopy(maindata, pos, reservBuf, 0, remains);
        }
        reserv = Math.max(remains, 0);
    }

    /** Puts the reservoir bytes the frame points back to in front of the frame's own data; false when they are not there. */
    private boolean restoreReservoir(Bits frame, int mainDataBegin) {
        int frameBytes = (frame.limit - frame.pos) / 8;
        int bytesHave = Math.min(reserv, mainDataBegin);
        System.arraycopy(reservBuf, Math.max(0, reserv - mainDataBegin), maindata, 0, bytesHave);
        System.arraycopy(frame.buf, frame.base + frame.pos / 8, maindata, bytesHave, frameBytes);
        int dataBytes = bytesHave + frameBytes;
        Arrays.fill(maindata, dataBytes, Math.min(maindata.length, dataBytes + MAIN_DATA_SLACK), (byte) 0);
        bs.init(maindata, 0, dataBytes);
        return reserv >= mainDataBegin;
    }

    private void l3Decode(int grBase, int nch) {
        for (int ch = 0; ch < nch; ch++) {
            Gr gr = grInfo[grBase + ch];
            int layer3grLimit = bs.pos + gr.part23Length;
            decodeScalefactors(header, 0, istPos[ch], bs, gr, scf, ch);
            huffman(grbuf, ch * 576, bs, gr, scf, layer3grLimit);
        }

        // both tests need the joint-stereo mode, which always has two channels
        if (testIStereo(header, 0)) {
            intensityStereo(grbuf, 0, istPos[1], grBase, header, 0);
        } else if (isMsStereo(header, 0)) {
            midsideStereo(grbuf, 0, 576);
        }

        for (int ch = 0; ch < nch; ch++) {
            Gr gr = grInfo[grBase + ch];
            int aaBands = 31;
            int nLongBands = (gr.mixedBlockFlag != 0 ? 2 : 0) << (mySampleRate(header, 0) == 2 ? 1 : 0);

            if (gr.nShortSfb != 0) {
                aaBands = nLongBands - 1;
                reorder(grbuf, ch * 576 + nLongBands * 18, syn, gr.sfbTab, gr.nLongSfb, 576 - nLongBands * 18);
            }

            antialias(grbuf, ch * 576, aaBands);
            imdctGr(grbuf, ch * 576, mdctOverlap, ch * 288, gr.blockType, nLongBands);
            changeSign(grbuf, ch * 576);
        }
    }

    // ---------------------------------------------------------------- polyphase synthesis

    private final float[] dctT = new float[32];

    /** minimp3's mp3d_DCT_II, scalar version: n columns of 32 rows with a stride of 18. */
    private void dct2(float[] g, int off, int n) {
        final float[] sec = Mp3Tables.SEC;
        final float[] t = dctT; // t[4][8], row-major
        for (int k = 0; k < n; k++) {
            int y = off + k;
            for (int i = 0; i < 8; i++) {
                float x0 = g[y + i * 18];
                float x1 = g[y + (15 - i) * 18];
                float x2 = g[y + (16 + i) * 18];
                float x3 = g[y + (31 - i) * 18];
                float t0 = x0 + x3;
                float t1 = x1 + x2;
                float t2 = (x1 - x2) * sec[3 * i + 0];
                float t3 = (x0 - x3) * sec[3 * i + 1];
                t[i] = t0 + t1;
                t[8 + i] = (t0 - t1) * sec[3 * i + 2];
                t[16 + i] = t3 + t2;
                t[24 + i] = (t3 - t2) * sec[3 * i + 2];
            }
            for (int i = 0, x = 0; i < 4; i++, x += 8) {
                float x0 = t[x], x1 = t[x + 1], x2 = t[x + 2], x3 = t[x + 3], x4 = t[x + 4], x5 = t[x + 5], x6 = t[x + 6], x7 = t[x + 7], xt;
                xt = x0 - x7;
                x0 += x7;
                x7 = x1 - x6;
                x1 += x6;
                x6 = x2 - x5;
                x2 += x5;
                x5 = x3 - x4;
                x3 += x4;
                x4 = x0 - x3;
                x0 += x3;
                x3 = x1 - x2;
                x1 += x2;
                t[x] = x0 + x1;
                t[x + 4] = (x0 - x1) * 0.70710677f;
                x5 = x5 + x6;
                x6 = (x6 + x7) * 0.70710677f;
                x7 = x7 + xt;
                x3 = (x3 + x4) * 0.70710677f;
                x5 -= x7 * 0.198912367f; // rotate by PI/8
                x7 += x5 * 0.382683432f;
                x5 -= x7 * 0.198912367f;
                x0 = xt - x6;
                xt += x6;
                t[x + 1] = (xt + x7) * 0.50979561f;
                t[x + 2] = (x4 + x3) * 0.54119611f;
                t[x + 3] = (x0 - x5) * 0.60134488f;
                t[x + 5] = (x0 + x5) * 0.89997619f;
                t[x + 6] = (x4 - x3) * 1.30656302f;
                t[x + 7] = (xt - x7) * 2.56291556f;
            }
            for (int i = 0; i < 7; i++, y += 4 * 18) {
                g[y + 0 * 18] = t[i];
                g[y + 1 * 18] = t[16 + i] + t[24 + i] + t[24 + i + 1];
                g[y + 2 * 18] = t[8 + i] + t[8 + i + 1];
                g[y + 3 * 18] = t[16 + i + 1] + t[24 + i] + t[24 + i + 1];
            }
            g[y + 0 * 18] = t[7];
            g[y + 1 * 18] = t[16 + 7] + t[24 + 7];
            g[y + 2 * 18] = t[8 + 7];
            g[y + 3 * 18] = t[24 + 7];
        }
    }

    private static void synthPair(float[] out, int p, int nch, float[] z, int zo) {
        float a;
        a = (z[zo + 14 * 64] - z[zo]) * 29;
        a += (z[zo + 1 * 64] + z[zo + 13 * 64]) * 213;
        a += (z[zo + 12 * 64] - z[zo + 2 * 64]) * 459;
        a += (z[zo + 3 * 64] + z[zo + 11 * 64]) * 2037;
        a += (z[zo + 10 * 64] - z[zo + 4 * 64]) * 5153;
        a += (z[zo + 5 * 64] + z[zo + 9 * 64]) * 6574;
        a += (z[zo + 8 * 64] - z[zo + 6 * 64]) * 37489;
        a += z[zo + 7 * 64] * 75038;
        out[p] = a;

        zo += 2;
        a = z[zo + 14 * 64] * 104;
        a += z[zo + 12 * 64] * 1567;
        a += z[zo + 10 * 64] * 9727;
        a += z[zo + 8 * 64] * 64019;
        a += z[zo + 6 * 64] * -9975;
        a += z[zo + 4 * 64] * -45;
        a += z[zo + 2 * 64] * 146;
        a += z[zo + 0 * 64] * -5;
        out[p + 16 * nch] = a;
    }

    private final float[] synA = new float[4];
    private final float[] synB = new float[4];

    private void synth(float[] x, int xl, float[] out, int dstl, int nch, float[] lins, int linsOff) {
        final float[] win = Mp3Tables.WIN;
        final float[] a = synA;
        final float[] bb = synB;
        int xr = xl + 576 * (nch - 1);
        int dstr = dstl + (nch - 1);
        int zlin = linsOff + 15 * 64;
        int w = 0;

        lins[zlin + 4 * 15] = x[xl + 18 * 16];
        lins[zlin + 4 * 15 + 1] = x[xr + 18 * 16];
        lins[zlin + 4 * 15 + 2] = x[xl];
        lins[zlin + 4 * 15 + 3] = x[xr];

        lins[zlin + 4 * 31] = x[xl + 1 + 18 * 16];
        lins[zlin + 4 * 31 + 1] = x[xr + 1 + 18 * 16];
        lins[zlin + 4 * 31 + 2] = x[xl + 1];
        lins[zlin + 4 * 31 + 3] = x[xr + 1];

        synthPair(out, dstr, nch, lins, linsOff + 4 * 15 + 1);
        synthPair(out, dstr + 32 * nch, nch, lins, linsOff + 4 * 15 + 64 + 1);
        synthPair(out, dstl, nch, lins, linsOff + 4 * 15);
        synthPair(out, dstl + 32 * nch, nch, lins, linsOff + 4 * 15 + 64);

        for (int i = 14; i >= 0; i--) {
            lins[zlin + 4 * i] = x[xl + 18 * (31 - i)];
            lins[zlin + 4 * i + 1] = x[xr + 18 * (31 - i)];
            lins[zlin + 4 * i + 2] = x[xl + 1 + 18 * (31 - i)];
            lins[zlin + 4 * i + 3] = x[xr + 1 + 18 * (31 - i)];
            lins[zlin + 4 * (i + 16)] = x[xl + 1 + 18 * (1 + i)];
            lins[zlin + 4 * (i + 16) + 1] = x[xr + 1 + 18 * (1 + i)];
            lins[zlin + 4 * (i - 16) + 2] = x[xl + 18 * (1 + i)];
            lins[zlin + 4 * (i - 16) + 3] = x[xr + 18 * (1 + i)];

            for (int k = 0; k < 8; k++) {
                float w0 = win[w++];
                float w1 = win[w++];
                int vz = zlin + 4 * i - k * 64;
                int vy = zlin + 4 * i - (15 - k) * 64;
                if (k == 0) {
                    for (int j = 0; j < 4; j++) {
                        bb[j] = lins[vz + j] * w1 + lins[vy + j] * w0;
                        a[j] = lins[vz + j] * w0 - lins[vy + j] * w1;
                    }
                } else if ((k & 1) == 1) { // S2
                    for (int j = 0; j < 4; j++) {
                        bb[j] += lins[vz + j] * w1 + lins[vy + j] * w0;
                        a[j] += lins[vy + j] * w1 - lins[vz + j] * w0;
                    }
                } else { // S1
                    for (int j = 0; j < 4; j++) {
                        bb[j] += lins[vz + j] * w1 + lins[vy + j] * w0;
                        a[j] += lins[vz + j] * w0 - lins[vy + j] * w1;
                    }
                }
            }

            out[dstr + (15 - i) * nch] = a[1];
            out[dstr + (17 + i) * nch] = bb[1];
            out[dstl + (15 - i) * nch] = a[0];
            out[dstl + (17 + i) * nch] = bb[0];
            out[dstr + (47 - i) * nch] = a[3];
            out[dstr + (49 + i) * nch] = bb[3];
            out[dstl + (47 - i) * nch] = a[2];
            out[dstl + (49 + i) * nch] = bb[2];
        }
    }

    private void synthGranule(float[] pcm, int pcmOff, int nbands, int nch) {
        for (int i = 0; i < nch; i++) {
            dct2(grbuf, 576 * i, nbands);
        }
        System.arraycopy(qmfState, 0, syn, 0, 15 * 64);
        for (int i = 0; i < nbands; i += 2) {
            synth(grbuf, i, pcm, pcmOff + 32 * nch * i, nch, syn, i * 64);
        }
        if (nch == 1) {
            // A mono frame only advances the even entries of the filter state (the odd ones belong to the right channel).
            // This is what the ISO reference decoder does, and it shows in a stream that switches between mono and stereo
            // (ISO conformance stream he_mode): the right channel then resumes from the state it had, not from the left one.
            for (int i = 0; i < 15 * 64; i += 2) {
                qmfState[i] = syn[nbands * 64 + i];
            }
        } else {
            System.arraycopy(syn, nbands * 64, qmfState, 0, 15 * 64);
        }
    }

    // ---------------------------------------------------------------- frame decoding

    /**
     * Decodes the first frame found in {@code mp3[off .. off+len)}.
     *
     * @param pcm  receives {@code samples * channels} floats in 16-bit units (interleaved), or null to only read the frame header
     * @param info receives how many bytes were consumed and what the frame is
     * @return the samples per channel produced (0 = this frame produced none: junk, a frame whose bit-reservoir data is
     *         missing, a corrupt frame, or an unsupported layer - {@code info.layer} tells; {@code info.frameBytes == 0}
     *         means there is nothing left to try)
     */
    public int decodeFrame(byte[] mp3, int off, int len, float[] pcm, FrameInfo info) {
        int i = 0;
        int frameSize = 0;
        boolean success = true;
        info.frameBytes = 0;
        info.frameOffset = 0;
        info.channels = 0;
        info.hz = 0;
        info.layer = 0;
        info.bitrateKbps = 0;

        if (len > 4 && (header[0] & 0xFF) == 0xFF && hdrCompare(header, 0, mp3, off)) {
            frameSize = hdrFrameBytes(mp3, off, freeFormatBytes) + hdrPadding(mp3, off);
            if (frameSize != len && (frameSize + HDR_SIZE > len || !hdrCompare(mp3, off, mp3, off + frameSize))) {
                frameSize = 0;
            }
        }
        if (frameSize == 0) {
            reset();
            i = findFrame(mp3, off, len);
            frameSize = foundFrameBytes;
            if (frameSize == 0 || i + frameSize > len) {
                info.frameBytes = i;
                return 0;
            }
        }

        int ho = off + i;
        System.arraycopy(mp3, ho, header, 0, HDR_SIZE);
        info.frameBytes = i + frameSize;
        info.frameOffset = i;
        info.channels = isMono(mp3, ho) ? 1 : 2;
        info.hz = hdrSampleRateHz(mp3, ho);
        info.layer = 4 - layerBits(mp3, ho);
        info.bitrateKbps = hdrBitrateKbps(mp3, ho);

        if (pcm == null) {
            return hdrFrameSamples(mp3, ho);
        }
        if (info.layer != 3) {
            header[0] = 0;
            return 0;
        }

        // minimp3 keeps the intensity-stereo positions in a scratch structure on the stack, and what a frame does not write
        // there is whatever the previous call left. Here they are zero at the start of every frame, so that a frame decodes
        // the same whatever came before it (the positions of the right channel are read with the band table of the left one,
        // and the two channels do not always have the same block type).
        Arrays.fill(istPos[0], 0);
        Arrays.fill(istPos[1], 0);

        Bits frame = new Bits();
        frame.init(mp3, ho + HDR_SIZE, frameSize - HDR_SIZE);
        if (isCrc(mp3, ho)) {
            frame.get(16);
        }

        try {
            int mainDataBegin = readSideInfo(frame, mp3, ho);
            if (mainDataBegin < 0 || frame.pos > frame.limit) {
                header[0] = 0;
                return 0;
            }
            success = restoreReservoir(frame, mainDataBegin);
            if (success) {
                int nch = info.channels;
                int out = 0;
                for (int igr = 0; igr < (testMpeg1(mp3, ho) ? 2 : 1); igr++, out += 576 * nch) {
                    Arrays.fill(grbuf, 0f);
                    l3Decode(igr * nch, nch);
                    synthGranule(pcm, out, 18, nch);
                }
            }
            saveReservoir();
        } catch (Corrupt c) {
            header[0] = 0;
            return 0;
        }
        return success ? hdrFrameSamples(header, 0) : 0;
    }

    /** The 16-bit sample for a decoded float (16-bit units): rounded half away from zero, clipped to the 16-bit range. */
    public static short toPcm16(float sample) {
        if (sample >= 32766.5f) {
            return 32767;
        }
        if (sample <= -32767.5f) {
            return -32768;
        }
        return (short) (int) (sample + (sample < 0 ? -0.5f : 0.5f));
    }

    // ---------------------------------------------------------------- whole files

    /**
     * Offset of the first byte after the ID3v2 tag(s) at the start of the file (0 when there is none). A tag is the 10-byte header
     * ("ID3", two version bytes that are not 0xFF, a flag byte, a size of four 7-bit bytes) and as many bytes as the size says, plus 10
     * more when the footer flag (0x10) is set; tags follow each other. A tag that claims more than the file holds ends the file.
     * Public - like {@link #isId3v1} and {@link #parseTagFrame} - so that the regression suite can pin each corner of the parsing on
     * its own: through {@link #decode(byte[])} alone, a footer that is skipped one byte short is invisible (the frame scanner steps over
     * junk), and only a second tag hidden behind it shows the difference.
     */
    public static int skipId3v2(byte[] f) {
        int pos = 0;
        while (pos + 10 <= f.length && f[pos] == 'I' && f[pos + 1] == 'D' && f[pos + 2] == '3'
            && (f[pos + 3] & 0xFF) != 0xFF && (f[pos + 4] & 0xFF) != 0xFF
            && (f[pos + 6] & 0x80) == 0 && (f[pos + 7] & 0x80) == 0 && (f[pos + 8] & 0x80) == 0 && (f[pos + 9] & 0x80) == 0) {
            int size = (f[pos + 6] << 21) | (f[pos + 7] << 14) | (f[pos + 8] << 7) | f[pos + 9];
            int footer = (f[pos + 5] & 0x10) != 0 ? 10 : 0;
            long next = (long) pos + 10 + size + footer;
            if (next > f.length) {
                return f.length; // a tag that claims more than the file holds: nothing to play
            }
            pos = (int) next;
        }
        return pos;
    }

    /** True when a 128-byte ID3v1 tag ("TAG" and 125 more bytes) starts at {@code at}. */
    public static boolean isId3v1(byte[] f, int at) {
        return at >= 0 && at + 128 <= f.length && f[at] == 'T' && f[at + 1] == 'A' && f[at + 2] == 'G';
    }

    /** What the metadata frame at the start of a file says (Xing / Info / VBRI): not audio, maybe gapless information. */
    public static final class TagFrame {
        /** Samples of encoder delay, or -1 when the tag does not say. */
        public int delay = -1;
        /** Samples of padding at the end, or -1. */
        public int padding = -1;
    }

    private static boolean tagAt(byte[] f, int o, int end, String tag) {
        if (o < 0 || o + tag.length() > end) {
            return false;
        }
        for (int i = 0; i < tag.length(); i++) {
            if (f[o + i] != (byte) tag.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Looks at the frame starting at {@code fo} (length {@code frameBytes}) and returns its tag if it is a Xing / Info / VBRI
     * metadata frame, else null. "Xing" or "Info" sits right after the side information (whose length depends on the version and
     * the channel mode, and on the 2 CRC bytes), "VBRI" 32 bytes after the header; a tag needs all four of its letters inside the
     * frame. The encoder delay and padding are given only by a LAME-style extension (24 bytes, after the optional fields the flags
     * announce) that lies entirely inside the frame; a tag without one is a tag all the same, with delay and padding -1.
     */
    public static TagFrame parseTagFrame(byte[] f, int fo, int frameBytes) {
        int end = fo + frameBytes;
        boolean mpeg1 = testMpeg1(f, fo);
        boolean mono = isMono(f, fo);
        int sideInfo = mpeg1 ? (mono ? 17 : 32) : (mono ? 9 : 17);
        int tagAt = fo + HDR_SIZE + (isCrc(f, fo) ? 2 : 0) + sideInfo;
        if (tagAt(f, fo + HDR_SIZE + 32, end, "VBRI")) {
            return new TagFrame(); // Fraunhofer's: a TOC, no gapless information
        }
        if (!(tagAt(f, tagAt, end, "Xing") || tagAt(f, tagAt, end, "Info"))) {
            return null;
        }
        TagFrame t = new TagFrame();
        if (tagAt + 8 > end) {
            return t;
        }
        int flags = f[tagAt + 7] & 0xFF; // the low byte of the 32-bit big-endian flags: only bits 0..3 mean anything
        int p = tagAt + 8;
        if ((flags & 1) != 0) {
            p += 4; // number of frames
        }
        if ((flags & 2) != 0) {
            p += 4; // number of bytes
        }
        if ((flags & 4) != 0) {
            p += 100; // table of contents
        }
        if ((flags & 8) != 0) {
            p += 4; // quality
        }
        // the LAME extension: 9 characters of encoder name, then 12 bytes later the delay and the padding in 3 bytes
        if (p + 24 <= end && (tagAt(f, p, end, "LAME") || tagAt(f, p, end, "Lavf") || tagAt(f, p, end, "Lavc"))) {
            int v = ((f[p + 21] & 0xFF) << 16) | ((f[p + 22] & 0xFF) << 8) | (f[p + 23] & 0xFF);
            t.delay = v >> 12;
            t.padding = v & 0xFFF;
        }
        return t;
    }

    /** Decoder delay of the MPEG audio synthesis filter bank, counted by every gapless-aware decoder (ffmpeg, LAME). */
    static final int DECODER_DELAY = 528 + 1;

    public static Pcm decode(byte[] file) throws FormatException {
        return decode(file, DEFAULT_MAX_SECONDS);
    }

    /**
     * Decodes a whole MP3 file held in memory. ID3 tags are skipped, a Xing/Info/VBRI metadata frame is not played, and the
     * encoder delay / padding of a LAME tag are cut off so that the sound starts on its first sample.
     *
     * @throws FormatException when no Layer III frame can be decoded, the file is Layer I/II, the sample rate or channel
     *                         count changes inside the file, or the sound is longer than {@code maxSeconds}
     */
    public static Pcm decode(byte[] file, int maxSeconds) throws FormatException {
        if (file == null) {
            throw new FormatException("no data");
        }
        try {
            return decodeUnchecked(file, maxSeconds);
        } catch (FormatException e) {
            throw e;
        } catch (RuntimeException e) {
            // Defence in depth for a decoder fed with arbitrary bytes: whatever slips through the guards is reported as
            // "not an MP3 we can play", the same way as any other bad file (the fuzz test in RegressionMp3Decoder exists to keep this empty).
            throw new FormatException("corrupt MP3 data (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static Pcm decodeUnchecked(byte[] file, int maxSeconds) throws FormatException {
        int start = skipId3v2(file);
        int end = file.length;
        if (end - start >= 128 && isId3v1(file, end - 128)) {
            end -= 128;
        }
        Mp3Decoder dec = new Mp3Decoder();
        FrameInfo info = new FrameInfo();
        float[] frame = new float[MAX_SAMPLES_PER_FRAME];
        short[] out = new short[1 << 16];
        int outLen = 0;
        int sampleRate = 0;
        int channels = 0;
        int audioFrames = 0;
        int samplesPerFrame = 0;
        TagFrame tag = null;
        boolean first = true;
        int pos = start;

        while (pos < end) {
            // The first frame found is looked at as a header only: it may be a metadata frame that must not be played.
            if (first) {
                dec.decodeFrame(file, pos, end - pos, null, info);
                if (info.layer == 0) {
                    break; // no frame, or one that does not fit in what is left
                }
                if (info.layer == 3) {
                    tag = parseTagFrame(file, pos + info.frameOffset, info.frameBytes - info.frameOffset);
                }
                first = false;
                if (tag != null) {
                    pos += info.frameBytes;
                    dec.reset();
                    continue;
                }
            }
            int n = dec.decodeFrame(file, pos, end - pos, frame, info);
            if (info.frameBytes <= 0) {
                break;
            }
            pos += info.frameBytes;
            if (info.layer == 1 || info.layer == 2) {
                throw new FormatException("MPEG audio Layer " + (info.layer == 1 ? "I" : "II") + " is not supported (only Layer III = MP3)");
            }
            if (n == 0) {
                continue;
            }
            if (sampleRate == 0) {
                sampleRate = info.hz;
                channels = info.channels;
                samplesPerFrame = n;
            } else if (info.hz != sampleRate || info.channels != channels) {
                throw new FormatException("the sample rate or the channel count changes inside the file");
            }
            long limit = (long) maxSeconds * sampleRate * channels;
            if ((long) outLen + n * channels > limit) {
                throw new FormatException("longer than " + maxSeconds + " seconds");
            }
            if (outLen + n * channels > out.length) {
                out = Arrays.copyOf(out, Math.max(out.length * 2, outLen + n * channels));
            }
            for (int k = 0; k < n * channels; k++) {
                out[outLen + k] = toPcm16(frame[k]);
            }
            outLen += n * channels;
            audioFrames++;
        }
        if (audioFrames == 0) {
            throw new FormatException("no decodable MP3 (Layer III) frame found");
        }

        int total = audioFrames * samplesPerFrame;
        int skip = 0;
        int keepEnd = total;
        if (tag != null && tag.delay >= 0) {
            skip = Math.min(total, tag.delay + DECODER_DELAY);
            keepEnd = Math.max(skip, Math.min(total, total - tag.padding + DECODER_DELAY));
        }
        short[] samples = Arrays.copyOfRange(out, skip * channels, keepEnd * channels);
        if (samples.length == 0) {
            throw new FormatException("the file holds no sound after the encoder delay is removed");
        }
        return new Pcm(sampleRate, channels, samples);
    }
}
