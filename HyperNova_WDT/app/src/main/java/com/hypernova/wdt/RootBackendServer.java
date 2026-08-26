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

/**
 * Development/demo root backend for HyperNova System Control.
 *
 * IMPORTANT:
 * - This class must NOT be launched by the Android Activity.
 * - Launch it from adb using `su 0` via start_root_backend.sh.
 * - It binds only to 127.0.0.1 and accepts a fixed allow-list of actions.
 * - No arbitrary shell command is accepted from the client.
 */
public final class RootBackendServer {
    private static final String HOST = "127.0.0.1";
    private static final int PORT = 47631;
    private static final String TOKEN = "HN_WDT_V1_6f0ca9d2b8c34c59a1f6e723";

    private RootBackendServer() {}

    public static void main(String[] args) throws Exception {
        final int uid = android.os.Process.myUid();
        System.out.println("HyperNova WDT root backend starting, uid=" + uid);

        if (uid != 0) {
            System.err.println("Refusing to run: backend must be uid 0");
            System.exit(13);
            return;
        }

        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(true);
            server.bind(
                    new InetSocketAddress(InetAddress.getByName(HOST), PORT),
                    8);

            System.out.println("READY " + HOST + ":" + PORT);

            while (true) {
                try (Socket client = server.accept()) {
                    handle(client);
                } catch (Throwable throwable) {
                    System.err.println("Client error: " + throwable);
                }
            }
        }
    }

    private static void handle(Socket client) throws Exception {
        client.setSoTimeout(3000);

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

        String action = line.substring(prefix.length()).trim();

        switch (action) {
            case "PING":
                reply(writer, "OK ROOT uid=" + android.os.Process.myUid());
                return;

            case "POWER_OFF":
                // Acknowledge before shutdown destroys the client connection.
                reply(writer, "OK POWER_OFF");
                Thread.sleep(120);
                logResult("POWER_OFF", runCommand("sync; reboot -p", 5));
                return;

            case "KERNEL_PANIC":
                // Acknowledge before the kernel panic destroys Android.
                reply(writer, "OK KERNEL_PANIC");
                Thread.sleep(120);
                logResult(
                        "KERNEL_PANIC",
                        runCommand(
                                "sync; "
                                        + "echo 0 > /proc/sys/kernel/panic; "
                                        + "echo 1 > /proc/sys/kernel/sysrq; "
                                        + "echo c > /proc/sysrq-trigger",
                                5));
                return;

            case "WATCHDOG":
                CommandResult watchdog =
                        runCommand(
                                "kill -STOP \"$(pidof watchdogd)\"",
                                5);

                if (watchdog.success) {
                    reply(writer, "OK WATCHDOG");
                } else {
                    reply(writer, "ERR WATCHDOG " + sanitize(watchdog.message));
                }
                return;

            default:
                reply(writer, "ERR UNKNOWN_ACTION");
        }
    }

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
                    process.waitFor(timeoutSeconds, TimeUnit.SECONDS);

            if (!finished) {
                process.destroy();
                return new CommandResult(false, "timeout");
            }

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(
                                    process.getInputStream(),
                                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() > 0) {
                        output.append(' ');
                    }
                    output.append(line);
                }
            }

            int exitCode = process.exitValue();
            if (exitCode == 0) {
                return new CommandResult(true, output.toString());
            }

            String message = output.length() == 0
                    ? "exit=" + exitCode
                    : "exit=" + exitCode + " " + output;
            return new CommandResult(false, message);
        } catch (Throwable throwable) {
            return new CommandResult(
                    false,
                    throwable.getClass().getSimpleName()
                            + ": "
                            + throwable.getMessage());
        }
    }

    private static void reply(
            BufferedWriter writer,
            String message) throws Exception {
        writer.write(message);
        writer.newLine();
        writer.flush();
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

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\n', ' ').replace('\r', ' ');
    }

    private static final class CommandResult {
        final boolean success;
        final String message;

        CommandResult(boolean success, String message) {
            this.success = success;
            this.message = message == null ? "" : message;
        }
    }
}
