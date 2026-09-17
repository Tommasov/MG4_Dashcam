package com.drivehub.kamera.helper.vehiclesensors;

import com.drivehub.kamera.dev.DevRuntimeLog;

import android.content.Context;

/**
 * Asks the vehicle what gear it is in, and reports whether the question is answerable at all.
 *
 * Reverse is the case worth catching early: the factory camera opens by itself and loses the
 * race against a dashcam that is still holding the devices. Knowing about R before the AVM
 * launches would end that race rather than shorten it.
 *
 * Two routes exist on this head unit and only one is cheap. The factory app reads gear through
 * SAIC's own vehiclesettings SDK (`CAR_GEAR_SIGNAL_ID = 5003`), which means binding to their
 * service. But the unit also ships stock `android.car.jar` and CarService, so the standard AAOS
 * property may be populated as well — and if it is, this costs nothing.
 *
 * Reflection rather than a compile-time dependency: android.car is a system library, not part
 * of the SDK this app builds against. Nothing here throws, and nothing here changes behaviour —
 * it writes what it found into the runtime log so the answer can be read off the car, which is
 * the only place the question can be settled.
 */
public final class VehicleGearProbe {

    /** VehiclePropertyIds.GEAR_SELECTION. */
    private static final int GEAR_SELECTION = 289408000;

    /** VehicleGear values. */
    private static final int GEAR_NEUTRAL = 0x0001;
    private static final int GEAR_REVERSE = 0x0002;
    private static final int GEAR_PARK = 0x0004;
    private static final int GEAR_DRIVE = 0x0008;

    private VehicleGearProbe() {
    }

    /** Runs once and records the outcome. Safe to call from anywhere. */
    public static void probe(Context context) {
        try {
            Object car = createCar(context);
            if (car == null) {
                DevRuntimeLog.add("GearProbe", "android.car unavailable");
                return;
            }
            Object manager = car.getClass()
                    .getMethod("getCarManager", String.class)
                    .invoke(car, "property");
            if (manager == null) {
                DevRuntimeLog.add("GearProbe", "no property manager");
                return;
            }
            Object value = manager.getClass()
                    .getMethod("getIntProperty", int.class, int.class)
                    .invoke(manager, GEAR_SELECTION, 0);
            int gear = value instanceof Integer ? (Integer) value : -1;
            DevRuntimeLog.add("GearProbe", "GEAR_SELECTION = " + gear + " (" + gearName(gear) + ")");
        } catch (Throwable t) {
            // Every failure mode lands here: no class, no method, no permission, property not
            // supported by this vehicle. The message says which.
            DevRuntimeLog.add("GearProbe", "unavailable: " + t);
        }
    }

    private static Object createCar(Context context) throws Exception {
        Class<?> carClass = Class.forName("android.car.Car");
        try {
            // API 29 and later.
            return carClass.getMethod("createCar", Context.class).invoke(null, context);
        } catch (NoSuchMethodException e) {
            DevRuntimeLog.add("GearProbe", "no synchronous createCar on this platform");
            return null;
        }
    }

    private static String gearName(int gear) {
        switch (gear) {
            case GEAR_NEUTRAL: return "N";
            case GEAR_REVERSE: return "R";
            case GEAR_PARK: return "P";
            case GEAR_DRIVE: return "D";
            default: return "?";
        }
    }
}
