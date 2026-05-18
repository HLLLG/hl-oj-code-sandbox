package com.hl.hlojcodesandbox.utils;

import cn.hutool.core.util.StrUtil;
import com.hl.hlojcodesandbox.model.ExecuteMessage;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@Slf4j
public class ProcessUtils {

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

        try {
            stdoutThread.start();
            stderrThread.start();

            long start = System.currentTimeMillis();
            // 超时控制
            boolean finished = runProcess.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - start;

            if (!finished) {
                runProcess.destroyForcibly();
                executeMessage.setExitCode(-1);
                executeMessage.setErrorMessage("Time Limit Exceeded");
                executeMessage.setTime(timeoutMs);
                return executeMessage;
            }

            stdoutThread.join();
            stderrThread.join();

            executeMessage.setExitCode(runProcess.exitValue());
            executeMessage.setTime(elapsed);
            executeMessage.setMessage(stdoutSb.toString().trim());
            executeMessage.setErrorMessage(stderrSb.toString().trim());
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
}
