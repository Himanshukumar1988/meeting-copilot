package com.copilot;

import com.sun.jna.Callback;
import com.sun.jna.Function;
import com.sun.jna.NativeLibrary;
import com.sun.jna.Pointer;

/**
 * macOS-only NSWindow tweaks that AWT does not expose, called through the Objective-C runtime.
 * Every method is a best-effort no-op (returns false) on other platforms or on failure.
 */
public final class MacWindow {
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase().contains("mac");
    private static final long NS_WINDOW_SHARING_NONE = 0;
    private static final long NS_WINDOW_SHARING_READ_ONLY = 1;

    private MacWindow() {}

    public static boolean isMac() { return MAC; }

    /** Hides the app's windows from screen sharing and recording (NSWindowSharingNone). */
    public static boolean setContentProtection(boolean on) {
        return forEachWindow("setSharingType:", on ? NS_WINDOW_SHARING_NONE : NS_WINDOW_SHARING_READ_ONLY);
    }

    /**
     * True if this process may capture other apps' windows. Without Screen Recording permission macOS silently
     * omits them (e.g. Chrome) from screenshots. Non-macOS always returns true.
     */
    public static boolean hasScreenCaptureAccess() {
        if (!MAC) return true;
        try {
            Function f = NativeLibrary.getInstance("ApplicationServices").getFunction("CGPreflightScreenCaptureAccess");
            return (f.invokeInt(new Object[0]) & 0xff) != 0;
        } catch (Throwable t) {
            return true; // unknown: don't block the user
        }
    }

    /** Asks macOS to show its Screen Recording permission prompt (takes effect after an app restart). */
    public static void requestScreenCaptureAccess() {
        if (!MAC) return;
        try {
            NativeLibrary.getInstance("ApplicationServices").getFunction("CGRequestScreenCaptureAccess").invokeInt(new Object[0]);
        } catch (Throwable ignored) {
            // best effort
        }
    }

    /** Lets the windows appear on every Space and over full-screen apps (e.g. a full-screen Chrome). */
    public static boolean setVisibleOnAllSpaces() {
        long canJoinAllSpaces = 1L, fullScreenAuxiliary = 1L << 8;
        return forEachWindow("setCollectionBehavior:", canJoinAllSpaces | fullScreenAuxiliary);
    }

    /** Lets mouse events pass through the app's windows to whatever is underneath. */
    public static boolean setClickThrough(boolean on) {
        return forEachWindow("setIgnoresMouseEvents:", on ? 1L : 0L);
    }

    /**
     * Makes this app the active app so a click into its text box can take keyboard focus. Needed because the
     * overlay starts non-focusable and macOS then does not activate the app on click. Main-thread dispatched.
     */
    public static void activateApp() {
        if (!MAC) return;
        onMainThread(() -> {
            NativeLibrary objc = NativeLibrary.getInstance("objc");
            Function send = objc.getFunction("objc_msgSend");
            Function cls = objc.getFunction("objc_getClass");
            Function sel = objc.getFunction("sel_registerName");
            Pointer app = send.invokePointer(new Object[] {
                cls.invokePointer(new Object[] {"NSApplication"}), sel.invokePointer(new Object[] {"sharedApplication"})});
            send.invokeVoid(new Object[] {app, sel.invokePointer(new Object[] {"activateIgnoringOtherApps:"}), 1L});
        });
    }

    private interface ThrowingRunnable { void run() throws Throwable; }

    private static boolean onMainThread(ThrowingRunnable task) {
        try {
            NativeLibrary system = NativeLibrary.getInstance("System");
            Pointer mainQueue = system.getGlobalVariableAddress("_dispatch_main_q");
            DispatchWork[] work = new DispatchWork[1];
            work[0] = ctx -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    System.err.println("MacWindow main-thread task failed: " + t);
                } finally {
                    IN_FLIGHT.remove(work[0]);
                }
            };
            IN_FLIGHT.add(work[0]);
            system.getFunction("dispatch_async_f").invokeVoid(new Object[] {mainQueue, Pointer.NULL, work[0]});
            return true;
        } catch (Throwable t) {
            System.err.println("MacWindow main-thread dispatch failed: " + t);
            return false;
        }
    }

    /** Callback type for libdispatch's dispatch_async_f. */
    public interface DispatchWork extends Callback {
        void invoke(Pointer context);
    }

    // Keeps callbacks reachable until libdispatch has run them (JNA would otherwise free the native stub).
    private static final java.util.Set<DispatchWork> IN_FLIGHT = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // Sends `selector(arg)` to every NSWindow of this process. AppKit may only be touched from the main
    // thread, so the work is queued onto the main dispatch queue; the window must already be visible.
    private static boolean forEachWindow(String selector, long arg) {
        if (!MAC) return false;
        try {
            NativeLibrary system = NativeLibrary.getInstance("System");
            // dispatch_get_main_queue() is a macro for the address of this global.
            Pointer mainQueue = system.getGlobalVariableAddress("_dispatch_main_q");
            DispatchWork[] work = new DispatchWork[1];
            work[0] = ctx -> {
                try {
                    applyToWindows(selector, arg);
                } catch (Throwable t) {
                    System.err.println("MacWindow." + selector + " failed: " + t);
                } finally {
                    IN_FLIGHT.remove(work[0]);
                }
            };
            IN_FLIGHT.add(work[0]);
            system.getFunction("dispatch_async_f").invokeVoid(new Object[] {mainQueue, Pointer.NULL, work[0]});
            return true;
        } catch (Throwable t) {
            System.err.println("MacWindow." + selector + " failed: " + t);
            return false;
        }
    }

    private static void applyToWindows(String selector, long arg) {
        NativeLibrary objc = NativeLibrary.getInstance("objc");
        Function send = objc.getFunction("objc_msgSend");
        Function cls = objc.getFunction("objc_getClass");
        Function sel = objc.getFunction("sel_registerName");

        Pointer app = send.invokePointer(new Object[] {
            cls.invokePointer(new Object[] {"NSApplication"}), sel.invokePointer(new Object[] {"sharedApplication"})});
        Pointer windows = send.invokePointer(new Object[] {app, sel.invokePointer(new Object[] {"windows"})});
        long count = send.invokeLong(new Object[] {windows, sel.invokePointer(new Object[] {"count"})});
        Pointer at = sel.invokePointer(new Object[] {"objectAtIndex:"});
        Pointer set = sel.invokePointer(new Object[] {selector});
        for (long i = 0; i < count; i++) {
            Pointer window = send.invokePointer(new Object[] {windows, at, i});
            send.invokeVoid(new Object[] {window, set, arg});
        }
    }
}
