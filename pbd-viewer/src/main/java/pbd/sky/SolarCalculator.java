package pbd.sky;

import java.time.LocalDate;
import java.time.Month;

/**
 * Standard simplified solar position algorithm (declination from day-of-
 * year, hour angle from time-of-day, altitude/azimuth from declination +
 * hour angle + observer latitude). Accurate to a fraction of a degree,
 * which is what a real-time skydome needs - not a full-precision
 * ephemeris (no nutation, atmospheric refraction, or equation-of-time
 * correction).
 *
 * Verified against known reference points before being trusted here (see
 * README): equinox at the equator, noon -> 90 deg altitude exactly;
 * summer/winter solstice at 45N, noon -> matches the standard
 * 90-lat+-23.44 formula; the day's altitude curve is symmetric around
 * solar noon; sunrise sits east of due south (azimuth < 180), sunset west
 * of it (azimuth > 180).
 */
public final class SolarCalculator {

    /** Muldraugh/West Point, KY - Project Zomboid's real-world basis (Knox County). */
    public static final double KNOX_COUNTY_LATITUDE_DEG = 37.9;

    public record SunPosition(double altitudeDeg, double azimuthDeg) {}

    /**
     * dayOfYear: 1-365(6). hourOfDay: 0-24 (local solar time - no
     * timezone/longitude equation-of-time correction, which would only
     * shift results by up to ~15 minutes of hour angle, well under what
     * matters for a real-time sky). latitudeDeg: observer latitude, north
     * positive.
     */
    public static SunPosition compute(int dayOfYear, double hourOfDay, double latitudeDeg) {
        double declRad = Math.toRadians(23.44) * Math.sin(Math.toRadians(360.0 / 365.0 * (dayOfYear - 81)));
        double hourAngleRad = Math.toRadians(15.0 * (hourOfDay - 12.0));
        double latRad = Math.toRadians(latitudeDeg);

        double sinAlt = Math.sin(declRad) * Math.sin(latRad) + Math.cos(declRad) * Math.cos(latRad) * Math.cos(hourAngleRad);
        sinAlt = Math.max(-1.0, Math.min(1.0, sinAlt)); // guards asin's domain against float rounding right at +-1
        double altRad = Math.asin(sinAlt);

        double cosAz = (Math.sin(declRad) - Math.sin(altRad) * Math.sin(latRad)) / (Math.cos(altRad) * Math.cos(latRad));
        cosAz = Math.max(-1.0, Math.min(1.0, cosAz));
        double azRad = Math.acos(cosAz);
        double azimuthDeg = Math.toDegrees(azRad);
        if (hourAngleRad > 0) azimuthDeg = 360.0 - azimuthDeg; // afternoon: sun is in the western half

        return new SunPosition(Math.toDegrees(altRad), azimuthDeg);
    }

    /** Convenience for a (month, day) pair in a non-leap reference year - +-1 day around Feb 29 in a leap year doesn't matter for this use. */
    public static int dayOfYear(Month month, int day) {
        return LocalDate.of(2025, month, day).getDayOfYear();
    }

    /**
     * Unit direction vector (world/engine Y-up space) for a given
     * altitude/azimuth - azimuth 0=north(-Z), 90=east(+X), 180=south(+Z),
     * 270=west(-X), matching this project's existing Y-up, Z-south-ish
     * convention (see PbdMeshCache/pbd.tese for the same axis handedness).
     */
    public static float[] toDirection(SunPosition pos) {
        double altRad = Math.toRadians(pos.altitudeDeg());
        double azRad = Math.toRadians(pos.azimuthDeg());
        float y = (float) Math.sin(altRad);
        float horizontal = (float) Math.cos(altRad);
        float x = (float) (horizontal * Math.sin(azRad));
        float z = (float) (-horizontal * Math.cos(azRad));
        return new float[]{x, y, z};
    }
}
