package com.hypernova.wdt;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public final class RootBackendServer {

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 47631;

    private static final String TOKEN =
            "HN_WDT_V1_6f0ca9d2b8c34c59a1f6e723";

    private RootBackendServer() {}

    public static void main(String[] args) throws Exception {

        int uid = android.os.Process.myUid();

        System.out.println(
                "HyperNova WDT backend starting uid=" + uid);

        /*
         * start_root_backend.sh must launch us using:
         *
         * su 0
         *
         * Therefore every command below already executes as root.
         */
        if (uid != 0) {
            System.err.println(
                    "ERROR: backend must run as uid 0");
            System.exit(13);
            return;
        }

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
                            "CLIENT ERROR: " + throwable);
                }
            }
        }
    }

    private static void handle(Socket client)
            throws Exception {

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

            reply(writer, "ERR AUTH");
            return;
        }

        String action =
                line.substring(prefix.length()).trim();

        System.out.println(
                "REQUEST " + action);

        switch (action) {

            case "PING":
                handlePing(writer);
                break;

            case "POWER_OFF":
                handlePowerOff(writer);
                break;

            case "KERNEL_PANIC":
                handleKernelPanic(writer);
                break;

            case "WATCHDOG":
                handleWatchdog(writer);
                break;

            default:
                reply(
                        writer,
                        "ERR UNKNOWN_ACTION");
                break;
        }
    }

    private static void handlePing(
            BufferedWriter writer)
            throws Exception {

        CommandResult result =
                runCommand(
                        "id -u",
                        3);

        if (result.success &&
                "0".equals(result.message.trim())) {

            reply(
                    writer,
                    "OK ROOT uid=0");

        } else {

            reply(
                    writer,
                    "ERR ROOT " +
                            sanitize(result.message));
        }
    }

    /*
     * EXACT TERMINAL FLOW:
     *
     * su 0
     * sync
     * reboot -p
     *
     * Backend is already running under su 0.
     */
    private static void handlePowerOff(
            BufferedWriter writer)
            throws Exception {

        /*
         * Acknowledge before Android disappears.
         */
        reply(
                writer,
                "OK POWER_OFF");

        System.out.println(
                "EXEC: sync; reboot -p");

        runCommand(
                "sync; reboot -p",
                10);
    }

    /*
     * EXACT TERMINAL FLOW:
     *
     * su 0
     * sync
     * echo 0 > /proc/sys/kernel/panic
     * echo 1 > /proc/sys/kernel/sysrq
     * echo c > /proc/sysrq-trigger
     *
     * Backend is already running under su 0.
     *
     * IMPORTANT:
     * All four commands are executed in ONE shell,
     * exactly in this order.
     */
    private static void handleKernelPanic(
            BufferedWriter writer)
            throws Exception {

        /*
         * Send acknowledgement first because
         * echo c will immediately destroy Android.
         */
        reply(
                writer,
                "OK KERNEL_PANIC");

        String command =
                "sync; "
                        + "echo 0 > /proc/sys/kernel/panic; "
                        + "echo 1 > /proc/sys/kernel/sysrq; "
                        + "echo c > /proc/sysrq-trigger";

        System.out.println(
                "EXEC: " + command);

        runCommand(
                command,
                10);
    }

    /*
     * EXACT TERMINAL FLOW:
     *
     * su 0
     * kill -STOP "$(pidof watchdogd)"
     *
     * Backend is already running under su 0.
     */
    private static void handleWatchdog(
            BufferedWriter writer)
            throws Exception {

        String command =
                "kill -STOP \"$(pidof watchdogd)\"";

        System.out.println(
                "EXEC: " + command);

        CommandResult result =
                runCommand(
                        command,
                        5);

        if (result.success) {

            reply(
                    writer,
                    "OK WATCHDOG");

        } else {

            reply(
                    writer,
                    "ERR WATCHDOG "
                            + sanitize(result.message));
        }
    }

    /*
     * IMPORTANT:
     *
     * NO su here.
     *
     * RootBackendServer itself is already uid=0 because
     * start_root_backend.sh launches app_process from su 0.
     */
    private static CommandResult runCommand(
            String command,
            long timeoutSeconds) {

        try {

            java.lang.Process process =
                    new ProcessBuilder(
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

            return new CommandResult(
                    false,
                    "exit="
                            + exitCode
                            + " "
                            + output);

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

    private static void reply(
            BufferedWriter writer,
            String message)
            throws Exception {

        writer.write(message);
        writer.newLine();
        writer.flush();

        System.out.println(
                "REPLY " + message);
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
