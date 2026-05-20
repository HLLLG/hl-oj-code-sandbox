package com.hl.hlojcodesandbox.utils;

import cn.hutool.core.util.StrUtil;
import com.hl.hlojcodesandbox.model.ExecuteMessage;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class ProcessUtils {

    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");
    /** 采样间隔；Windows tasklist 本身就慢，无需再压更短 */
    private static final long SAMPLE_INTERVAL_MS = IS_WINDOWS ? 200L : 100L;

    /**
     * 执行进程并获取输出（用于编译，无 stdin）。
     * stdout / stderr 由独立线程并发读取，避免缓冲区满导致进程挂起。
     */
    public static ExecuteMessage runProcessAndGetMessage(Process runProcess, String opName) {
        ExecuteMessage executeMessage = new ExecuteMessage();
        StringBuilder stdoutSb = new StringBuilder();
        StringBuilder stderrSb = new StringBuilder();

        Thread stdoutThread = buildReaderThread(runProcess.getInputStream(), stdoutSb, opName + " stdout");
        Thread stderrThread = buildReaderThread(runProcess.getErrorStream(), stderrSb, opName + " stderr");

        try {
            stdoutThread.start();
            stderrThread.start();

            long start = System.currentTimeMillis();
            int exitCode = runProcess.waitFor();
            long end = System.currentTimeMillis();

            stdoutThread.join();
            stderrThread.join();

            executeMessage.setExitCode(exitCode);
            executeMessage.setTime(end - start);
            executeMessage.setMessage(stdoutSb.toString().trim());
            executeMessage.setErrorMessage(stderrSb.toString().trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("{} process interrupted", opName, e);
        }
        return executeMessage;
    }

    /**
     * 执行进程并通过 stdin 传入输入参数（用于运行用户代码）。
     * 超时后强制终止进程并标记 TLE。
     */
    public static ExecuteMessage runInteractProcessAndGetMessage(Process runProcess, String args, long timeoutMs) {
        ExecuteMessage executeMessage = new ExecuteMessage();

        // 写入 stdin，关闭后子进程收到 EOF
        try (OutputStream stdin = runProcess.getOutputStream()) {
            if (StrUtil.isNotEmpty(args)) {
                stdin.write((args + "\n").getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
        } catch (IOException e) {
            log.error("stdin write error", e);
        }

        StringBuilder stdoutSb = new StringBuilder();
        StringBuilder stderrSb = new StringBuilder();

        Thread stdoutThread = buildReaderThread(runProcess.getInputStream(), stdoutSb, "exec stdout");
        Thread stderrThread = buildReaderThread(runProcess.getErrorStream(), stderrSb, "exec stderr");

        // 内存采样：记录峰值（KB）
        AtomicLong peakMemoryKb = new AtomicLong(0L);
        Thread memoryThread = buildMemorySampler(runProcess, peakMemoryKb);

        try {
            stdoutThread.start();
            stderrThread.start();
            memoryThread.start();

            long start = System.currentTimeMillis();
            // 超时控制
            boolean finished = runProcess.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;

            if (!finished) {
                runProcess.destroyForcibly();
                memoryThread.interrupt();
                executeMessage.setExitCode(-1);
                executeMessage.setErrorMessage("Time Limit Exceeded");
                executeMessage.setTime(timeoutMs);
                executeMessage.setMemory(peakMemoryKb.get());
                return executeMessage;
            }

            stdoutThread.join();
            stderrThread.join();
            memoryThread.interrupt();
            memoryThread.join(500);

            executeMessage.setExitCode(runProcess.exitValue());
            executeMessage.setTime(elapsed);
            executeMessage.setMessage(stdoutSb.toString().trim());
            executeMessage.setErrorMessage(stderrSb.toString().trim());
            executeMessage.setMemory(peakMemoryKb.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("exec process interrupted", e);
        }
        return executeMessage;
    }

    private static Thread buildReaderThread(java.io.InputStream is, StringBuilder sb, String name) {
        return new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
            } catch (IOException e) {
                log.error("{} read error", name, e);
            }
        }, name);
    }

    /**
     * 启动一个守护采样线程，循环读取子进程的内存占用并更新峰值（KB）。
     * 进程退出或被 interrupt 后自然结束。
     */
    private static Thread buildMemorySampler(Process process, AtomicLong peakKb) {
        long pid = process.pid();
        Thread t = new Thread(() -> {
            while (process.isAlive() && !Thread.currentThread().isInterrupted()) {
                long sample = readMemoryKb(pid);
                if (sample > 0 && sample > peakKb.get()) {
                    peakKb.set(sample);
                }
                try {
                    Thread.sleep(SAMPLE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "mem-sampler-" + pid);
        t.setDaemon(true);
        return t;
    }

    /** 返回当前 RSS（KB）；读取失败返回 0 */
    private static long readMemoryKb(long pid) {
        return IS_WINDOWS ? readMemoryKbWindows(pid) : readMemoryKbLinux(pid);
    }

    private static long readMemoryKbLinux(long pid) {
        File status = new File("/proc/" + pid + "/status");
        if (!status.exists()) {
            return 0L;
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(status))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    // 格式: "VmRSS:   12345 kB"
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        return Long.parseLong(parts[1]);
                    }
                }
            }
        } catch (IOException | NumberFormatException ignore) {
        }
        return 0L;
    }

    /**
     * Windows 下用 tasklist 取内存。tasklist 输出形如：
     *   "java.exe","12345","Console","1","123,456 K"
     * 取最后一列 "123,456 K"，去逗号转 KB。
     */
    private static long readMemoryKbWindows(long pid) {
        try {
            Process p = new ProcessBuilder(
                    "tasklist", "/FI", "PID eq " + pid, "/FO", "CSV", "/NH")
                    .redirectErrorStream(true)
                    .start();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank() || !line.contains("\"" + pid + "\"")) {
                        continue;
                    }
                    int last = line.lastIndexOf('"');
                    int prev = line.lastIndexOf('"', last - 1);
                    if (prev < 0) {
                        continue;
                    }
                    String mem = line.substring(prev + 1, last).trim();
                    // 去掉 " K" / " KB" 后缀和千分位逗号
                    mem = mem.replace(",", "").replace(" K", "").replace("K", "").trim();
                    if (mem.isEmpty()) {
                        continue;
                    }
                    return Long.parseLong(mem);
                }
            }
            p.waitFor(1, TimeUnit.SECONDS);
        } catch (IOException | InterruptedException | NumberFormatException ignore) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            }
        }
        return 0L;
    }
}
