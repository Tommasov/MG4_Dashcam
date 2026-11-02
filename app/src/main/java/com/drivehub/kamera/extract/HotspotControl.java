package com.drivehub.kamera.extract;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ResultReceiver;

import com.drivehub.kamera.dev.DevRuntimeLog;

import java.lang.reflect.Method;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The vehicle's own access point, read and switched from code.
 *
 * <p>Everything here was measured on the car first, by a separate probe app, because none of it
 * can be read from a desk. What that probe found on 2026-09-24, on firmware 17692:
 *
 * <ul>
 *   <li>{@code getWifiApState()} answers - 11, disabled.</li>
 *   <li>{@code getWifiApConfiguration()} answers with the real thing: SSID <i>MG 4</i> and a
 *       fourteen-character passphrase, <b>readable</b>. That is what makes a QR code possible:
 *       the phone joins the access point the car already has, with the password the owner
 *       already set, and nobody types anything.</li>
 *   <li>{@code setWifiApEnabled} <b>is not present</b>. The old one-line way of switching an
 *       access point on does not exist on this platform.</li>
 *   <li>{@code startTethering} is present on the connectivity service.</li>
 * </ul>
 *
 * <p>So switching it on goes through tethering, and that is awkward for a reason worth writing
 * down: the public-ish {@code ConnectivityManager.startTethering} wants a callback of a hidden
 * <i>abstract class</i>, which reflection cannot subclass. The system service behind it takes a
 * {@link ResultReceiver} instead, and ResultReceiver is ordinary public API - so the call is
 * made to the service directly, through its AIDL interface, with a receiver we can actually
 * build. It needs TETHER_PRIVILEGED, which this app holds by being signed with the platform key.
 *
 * <p>If any of that is refused, every method here fails quietly and says what it saw - including
 * the signatures the platform does offer, so the next build knows what to call instead of
 * guessing again.
 */
public final class HotspotControl {

    private static final String TAG = "Hotspot";

    /** ConnectivityManager.TETHERING_WIFI. */
    private static final int TETHERING_WIFI = 0;

    public static final int STATE_DISABLING = 10;
    public static final int STATE_DISABLED = 11;
    public static final int STATE_ENABLING = 12;
    public static final int STATE_ENABLED = 13;
    public static final int STATE_FAILED = 14;
    public static final int STATE_UNKNOWN = -1;

    /** Android's default soft-AP address. Worth preferring when several interfaces are up. */
    private static final String SOFT_AP_ADDRESS_PREFIX = "192.168.43.";

    private HotspotControl() {
    }

    /** What the phone has to be told, in the two forms it can be told in. */
    public static final class Credentials {
        public final String ssid;
        public final String passphrase;

        Credentials(String ssid, String passphrase) {
            this.ssid = ssid;
            this.passphrase = passphrase;
        }

        /**
         * The string a QR code carries so that scanning it joins the network.
         *
         * <p>The format every phone camera understands. Backslash, semicolon, comma and colon
         * have to be escaped inside the fields or a password containing one would silently
         * produce a QR code that joins nothing.
         */
        public String wifiQrPayload() {
            return "WIFI:T:WPA;S:" + escape(ssid) + ";P:" + escape(passphrase) + ";;";
        }

        private static String escape(String value) {
            if (value == null) {
                return "";
            }
            StringBuilder b = new StringBuilder(value.length() + 4);
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c == '\\' || c == ';' || c == ',' || c == ':' || c == '"') {
                    b.append('\\');
                }
                b.append(c);
            }
            return b.toString();
        }
    }

    public static int state(Context context) {
        WifiManager wifi = wifiManager(context);
        if (wifi == null) {
            return STATE_UNKNOWN;
        }
        Object state = invoke(wifi, "getWifiApState");
        return state instanceof Integer ? (Integer) state : STATE_UNKNOWN;
    }

    public static boolean isEnabled(Context context) {
        return state(context) == STATE_ENABLED;
    }

    /** The access point's name and password, or null if this platform will not say. */
    public static Credentials credentials(Context context) {
        WifiManager wifi = wifiManager(context);
        if (wifi == null) {
            return null;
        }
        Object config = invoke(wifi, "getWifiApConfiguration");
        if (config == null || config instanceof Throwable) {
            DevRuntimeLog.add(TAG, "cannot read the access point configuration: "
                    + (config == null ? "null" : config.toString()));
            return null;
        }
        String ssid = stringField(config, "SSID");
        String key = stringField(config, "preSharedKey");
        if (ssid == null || ssid.isEmpty()) {
            DevRuntimeLog.add(TAG, "the access point configuration has no SSID");
            return null;
        }
        return new Credentials(ssid, key == null ? "" : key);
    }

    /**
     * Asks for the access point to be switched on.
     *
     * @return true if the request was accepted; the access point is then still coming up, which
     *         {@link #awaitEnabled} waits for.
     */
    public static boolean start(Context context) {
        Object service = connectivityService();
        if (service == null) {
            return false;
        }
        ResultReceiver receiver = new ResultReceiver(new Handler(Looper.getMainLooper())) {
            @Override
            protected void onReceiveResult(int resultCode, Bundle data) {
                // 0 is TETHER_ERROR_NO_ERROR. Anything else is worth reading in the report
                // rather than being turned into a guess here.
                DevRuntimeLog.add(TAG, "tethering request answered with code " + resultCode
                        + (resultCode == 0 ? " (no error)" : ""));
            }
        };
        try {
            Method start = findMethod(service, "startTethering",
                    int.class, ResultReceiver.class, boolean.class, String.class);
            if (start == null) {
                describeSignatures(service, "startTethering");
                return false;
            }
            start.invoke(service, TETHERING_WIFI, receiver, false, context.getPackageName());
            DevRuntimeLog.add(TAG, "asked the connectivity service to start the access point");
            return true;
        } catch (Throwable t) {
            DevRuntimeLog.add(TAG, "could not start the access point: " + t);
            return false;
        }
    }

    /** Switches the access point off again. Only ever called if we switched it on. */
    public static void stop(Context context) {
        Object service = connectivityService();
        if (service == null) {
            return;
        }
        try {
            Method stop = findMethod(service, "stopTethering", int.class);
            if (stop == null) {
                stop = findMethod(service, "stopTethering", int.class, String.class);
                if (stop != null) {
                    stop.invoke(service, TETHERING_WIFI, context.getPackageName());
                    DevRuntimeLog.add(TAG, "access point switched off");
                    return;
                }
                describeSignatures(service, "stopTethering");
                return;
            }
            stop.invoke(service, TETHERING_WIFI);
            DevRuntimeLog.add(TAG, "access point switched off");
        } catch (Throwable t) {
            DevRuntimeLog.add(TAG, "could not switch the access point off: " + t);
        }
    }

    /** Waits for the access point to report itself enabled. Not for the main thread. */
    public static boolean awaitEnabled(Context context, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        int last = STATE_UNKNOWN;
        while (System.currentTimeMillis() < deadline) {
            int now = state(context);
            if (now != last) {
                DevRuntimeLog.add(TAG, "access point state " + now
                        + (now == STATE_ENABLED ? " (enabled)" : ""));
                last = now;
            }
            if (now == STATE_ENABLED) {
                return true;
            }
            if (now == STATE_FAILED) {
                return false;
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        DevRuntimeLog.add(TAG, "the access point did not come up within "
                + (timeoutMs / 1000) + "s; last state " + last);
        return false;
    }

    /**
     * Every address a phone could try, best first.
     *
     * <p>Which interface carries the access point is not knowable in advance: on a single-radio
     * chipset it can be the same wlan0 that was a client a moment ago, and on others it appears
     * as ap0 or swlan0. Rather than deciding, this lists them with the likeliest first - the
     * soft-AP subnet, then the interfaces named like an access point - and the screen shows the
     * first while keeping the rest to hand.
     */
    public static List<String> addresses() {
        List<String> best = new ArrayList<>();
        List<String> likely = new ArrayList<>();
        List<String> rest = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                String name = ni.getName() == null ? "" : ni.getName();
                for (InterfaceAddress a : ni.getInterfaceAddresses()) {
                    InetAddress ip = a.getAddress();
                    if (!(ip instanceof Inet4Address)) {
                        continue;
                    }
                    String address = ip.getHostAddress();
                    if (address.startsWith(SOFT_AP_ADDRESS_PREFIX)) {
                        best.add(address);
                    } else if (name.startsWith("ap") || name.startsWith("swlan")
                            || name.startsWith("wlan")) {
                        likely.add(address);
                    } else {
                        rest.add(address);
                    }
                }
            }
        } catch (Throwable t) {
            DevRuntimeLog.add(TAG, "could not list the interfaces: " + t);
        }
        best.addAll(likely);
        best.addAll(rest);
        return best;
    }

    /** One line for the report: what this platform offers, whatever happens next. */
    public static String describe(Context context) {
        Object service = connectivityService();
        WifiManager wifi = wifiManager(context);
        Credentials credentials = credentials(context);
        return String.format(Locale.US,
                "access point: state %d, credentials %s, setWifiApEnabled %s,"
                        + " connectivity service %s, addresses %s",
                state(context),
                credentials == null ? "unreadable" : "readable",
                wifi != null && findMethod(wifi, "setWifiApEnabled") != null ? "present" : "absent",
                service == null ? "unreachable" : "reachable",
                addresses());
    }

    // ---------- plumbing ----------

    private static WifiManager wifiManager(Context context) {
        return (WifiManager) context.getApplicationContext()
                .getSystemService(Context.WIFI_SERVICE);
    }

    /**
     * The connectivity system service itself, behind ConnectivityManager.
     *
     * <p>Reached rather than the manager because the manager's own entry point wants a callback
     * whose class reflection cannot extend. See the class comment.
     */
    private static Object connectivityService() {
        try {
            Class<?> serviceManager = Class.forName("android.os.ServiceManager");
            IBinder binder = (IBinder) serviceManager
                    .getMethod("getService", String.class).invoke(null, "connectivity");
            if (binder == null) {
                DevRuntimeLog.add(TAG, "no connectivity service binder");
                return null;
            }
            Class<?> stub = Class.forName("android.net.IConnectivityManager$Stub");
            return stub.getMethod("asInterface", IBinder.class).invoke(null, binder);
        } catch (Throwable t) {
            DevRuntimeLog.add(TAG, "cannot reach the connectivity service: " + t);
            return null;
        }
    }

    private static Method findMethod(Object target, String name, Class<?>... types) {
        if (target == null) {
            return null;
        }
        for (Class<?> c : target.getClass().getInterfaces()) {
            try {
                return c.getMethod(name, types);
            } catch (Throwable ignored) {
                // Try the next interface, then the class itself.
            }
        }
        try {
            return target.getClass().getMethod(name, types);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Writes down what the platform does offer under a name we failed to call. */
    private static void describeSignatures(Object target, String name) {
        StringBuilder b = new StringBuilder("no ").append(name)
                .append(" we can call; what is offered:");
        boolean any = false;
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name)) {
                continue;
            }
            any = true;
            b.append("\n    ").append(name).append('(');
            Class<?>[] types = m.getParameterTypes();
            for (int i = 0; i < types.length; i++) {
                b.append(i == 0 ? "" : ", ").append(types[i].getSimpleName());
            }
            b.append(')');
        }
        DevRuntimeLog.add(TAG, any ? b.toString() : "no method called " + name + " at all");
    }

    private static Object invoke(Object target, String name) {
        try {
            Method m = target.getClass().getMethod(name);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) {
            return t;
        }
    }

    private static String stringField(Object target, String name) {
        try {
            Object value = target.getClass().getField(name).get(target);
            return value == null ? null : value.toString();
        } catch (Throwable t) {
            return null;
        }
    }
}
