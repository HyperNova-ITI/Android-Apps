package com.hypernova.wdt;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Root backend for HyperNova System Control.
 *
 * Architecture:
 *
 * Android UI
 *   -> localhost TCP
 *   -> RootBackendServer
 *   -> fixed allow-listed action
 *   -> su 0
 *   -> system command
 *
 * No arbitrary command is accepted from the Android application.
 */
public final class RootBackendServer {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 47631;

    private static final String TOKEN =
            "HN_WDT_V1_6f0ca9d2b8c34c59a1f6e723";

    private RootBackendServer() {}

    public static void main(String[] args) throws Exception {

        final int uid = android.os.Process.myUid();

        System.out.println(
                "HyperNova WDT root backend starting, uid=" + uid);

        if (uid != 0) {
            System.err.println(
                    "Refusing to run: backend must be uid 0");
            System.exit(13);
            return;
        }

        System.out.println(
                "su binary: " + findSuBinary());

        try (ServerSocket server = new ServerSocket()) {

            server.setReuseAddress(true);

            server.bind(
                    new InetSocketAddress(
                            InetAddress.getByName(HOST),
                            PORT),
                    8);

            System.out.println(
                    "READY " + HOST + ":" + PORT);

            while (true) {

                try (Socket client = server.accept()) {

                    handle(client);

                } catch (Throwable throwable) {

                    System.err.println(
                            "Client error: " + throwable);
                }
            }
        }
    }

    private static void handle(
            Socket client) throws Exception {

        client.setSoTimeout(5000);

        BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                client.getInputStream(),
                                StandardCharsets.UTF_8));

        BufferedWriter writer =
                new BufferedWriter(
                        new OutputStreamWriter(
                                client.getOutputStream(),
                                StandardCharsets.UTF_8));

        String line = reader.readLine();

        if (line == null) {
            return;
        }

        String prefix = TOKEN + " ";

        if (!line.startsWith(prefix)) {

            reply(
                    writer,
                    "ERR AUTH");

            return;
        }

        String action =
                line.substring(
                        prefix.length())
                        .trim();

        System.out.println(
                "REQUEST action=" + action);

        switch (action) {

            case "PING":
                handlePing(writer);
                return;

            case "POWER_OFF":
                handlePowerOff(writer);
                return;

            case "KERNEL_PANIC":
                handleKernelPanic(writer);
                return;

            case "WATCHDOG":
                handleWatchdog(writer);
                return;

            default:

                reply(
                        writer,
                        "ERR UNKNOWN_ACTION");
        }
    }

    private static void handlePing(
            BufferedWriter writer) throws Exception {

        CommandResult root =
                runAsSu(
                        "id",
                        3);

        if (root.success) {

            reply(
                    writer,
                    "OK ROOT "
                            + sanitize(root.message));

        } else {

            reply(
                    writer,
                    "ERR ROOT "
                            + sanitize(root.message));
        }
    }

    /**
     * User requested command:
     *
     * su 0
     * sync
     * reboot -p
     */
    private static void handlePowerOff(
            BufferedWriter writer) throws Exception {

        /*
         * Reply first because a successful power off
         * destroys Android and therefore the TCP connection.
         */
        reply(
                writer,
                "OK POWER_OFF");

        Thread.sleep(150);

        CommandResult result =
                runAsSu(
                        "sync; reboot -p",
                        8);

        logResult(
                "POWER_OFF",
                result);
    }

    /**
     * User requested command:
     *
     * su 0
     * sync
     * echo 0 > /proc/sys/kernel/panic
     * echo 1 > /proc/sys/kernel/sysrq
     * echo c > /proc/sysrq-trigger
     */
    private static void handleKernelPanic(
            BufferedWriter writer) throws Exception {

        /*
         * Do the non-destructive configuration first.
         *
         * This lets us report an actual error to the UI
         * if sysrq/kernel procfs access is denied.
         */
        CommandResult prepare =
                runAsSu(
                        "sync; "
                                + "echo 0 > /proc/sys/kernel/panic; "
                                + "echo 1 > /proc/sys/kernel/sysrq",
                        5);

        if (!prepare.success) {

            reply(
                    writer,
                    "ERR KERNEL_PANIC PREPARE "
                            + sanitize(prepare.message));

            logResult(
                    "KERNEL_PANIC_PREPARE",
                    prepare);

            return;
        }

        /*
         * Acknowledge immediately before the destructive trigger.
         * A successful trigger will kill the Android/Linux system
         * before another response can be sent.
         */
        reply(
                writer,
                "OK KERNEL_PANIC");

        Thread.sleep(150);

        CommandResult trigger =
                runAsSu(
                        "echo c > /proc/sysrq-trigger",
                        5);

        /*
         * Normally execution never reaches here if panic succeeds.
         * If it does return, the log tells us what failed.
         */
        logResult(
                "KERNEL_PANIC_TRIGGER",
                trigger);
    }

    /**
     * User requested command:
     *
     * su 0
     * kill -STOP "$(pidof watchdogd)"
     */
    private static void handleWatchdog(
            BufferedWriter writer) throws Exception {

        /*
         * First verify watchdogd exists.
         */
        CommandResult pidResult =
                runAsSu(
                        "pidof watchdogd",
                        3);

        if (!pidResult.success
                || pidResult.message.trim().isEmpty()) {

            reply(
                    writer,
                    "ERR WATCHDOG watchdogd not found");

            logResult(
                    "WATCHDOG_PID",
                    pidResult);

            return;
        }

        System.out.println(
                "WATCHDOG pid="
                        + sanitize(pidResult.message));

        /*
         * Execute the exact requested watchdog command.
         */
        CommandResult watchdog =
                runAsSu(
                        "kill -STOP \"$(pidof watchdogd)\"",
                        5);

        if (watchdog.success) {

            reply(
                    writer,
                    "OK WATCHDOG");

            System.out.println(
                    "WATCHDOG STOP signal sent");

        } else {

            reply(
                    writer,
                    "ERR WATCHDOG "
                            + sanitize(watchdog.message));
        }

        logResult(
                "WATCHDOG",
                watchdog);
    }

    /**
     * Execute a FIXED backend command through su 0.
     *
     * This is intentionally backend-only. The Android Activity never
     * receives arbitrary shell text and never invokes su itself.
     */
    private static CommandResult runAsSu(
            String command,
            long timeoutSeconds) {

        try {

            String suBinary =
                    findSuBinary();

            System.out.println(
                    "EXEC su 0: " + command);

            java.lang.Process process =
                    new ProcessBuilder(
                            suBinary,
                            "0",
                            "/system/bin/sh",
                            "-c",
                            command)
                            .redirectErrorStream(true)
                            .start();

            boolean finished =
                    process.waitFor(
                            timeoutSeconds,
                            TimeUnit.SECONDS);

            if (!finished) {

                process.destroy();

                return new CommandResult(
                        false,
                        "timeout");
            }

            StringBuilder output =
                    new StringBuilder();

            try (BufferedReader commandReader =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream(),
                                    StandardCharsets.UTF_8))) {

                String outputLine;

                while ((outputLine =
                        commandReader.readLine()) != null) {

                    if (output.length() > 0) {
                        output.append(' ');
                    }

                    output.append(outputLine);
                }
            }

            int exitCode =
                    process.exitValue();

            if (exitCode == 0) {

                return new CommandResult(
                        true,
                        output.toString());
            }

            String message =
                    output.length() == 0
                            ? "exit=" + exitCode
                            : "exit="
                                    + exitCode
                                    + " "
                                    + output;

            return new CommandResult(
                    false,
                    message);

        } catch (Throwable throwable) {

            return new CommandResult(
                    false,
                    throwable
                            .getClass()
                            .getSimpleName()
                            + ": "
                            + throwable.getMessage());
        }
    }

    /**
     * AOSP/userdebug images commonly expose su in one of these paths.
     */
    private static String findSuBinary() {

        String[] candidates = {
                "/system/xbin/su",
                "/system/bin/su",
                "/vendor/bin/su"
        };

        for (String candidate : candidates) {

            File file =
                    new File(candidate);

            if (file.exists()
                    && file.canExecute()) {

                return candidate;
            }
        }

        /*
         * PATH fallback.
         * The device already supports `su 0` from adb shell.
         */
        return "su";
    }

    private static void reply(
            BufferedWriter writer,
            String message) throws Exception {

        writer.write(message);
        writer.newLine();
        writer.flush();

        System.out.println(
                "REPLY " + message);
    }

    private static void logResult(
            String action,
            CommandResult result) {

        System.out.println(
                action
                        + " success="
                        + result.success
                        + " message="
                        + sanitize(result.message));
    }

    private static String sanitize(
            String value) {

        if (value == null) {
            return "";
        }

        return value
                .replace('\n', ' ')
                .replace('\r', ' ');
    }

    private static final class CommandResult {

        final boolean success;
        final String message;

        CommandResult(
                boolean success,
                String message) {

            this.success = success;

            this.message =
                    message == null
                            ? ""
                            : message;
        }
    }
}
