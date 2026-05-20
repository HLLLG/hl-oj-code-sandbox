package com.hl.hlojcodesandbox;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.dfa.WordTree;
import com.hl.hlojcodesandbox.model.CodeSandboxRequest;
import com.hl.hlojcodesandbox.model.CodeSandboxResult;
import com.hl.hlojcodesandbox.model.ExecuteMessage;
import com.hl.hlojcodesandbox.model.JudgeInfo;
import com.hl.hlojcodesandbox.utils.ProcessUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Java 代码沙箱模板：固化"安全检查 → 落盘 → 编译 → 执行 → 整理结果 → 清理"流程，
 * 子类只需实现 {@link #runCode(File, List)} 完成具体执行环节。
 */
@Slf4j
public abstract class JavaCodeSandboxTemplate implements CodeSandbox {

    protected static final String GLOBAL_CODE_DIR_NAME = "tmpCode";
    protected static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";
    protected static final long DEFAULT_TIME_OUT = 5000L;

    private static final List<String> BLACK_LIST = Arrays.asList(
            // 进程与命令执行
            "exec", "Runtime", "ProcessBuilder",
            // 强制退出 JVM
            "System.exit",
            // 网络访问
            "Socket", "ServerSocket", "HttpURLConnection",
            // 反射与动态类加载
            "ClassLoader", "Class.forName",
            // 文件写入（读取 stdin 的 BufferedReader 不受影响）
            "FileWriter", "FileOutputStream", "RandomAccessFile",
            // 危险的 NIO 文件操作
            "Files.write", "Files.delete", "Files.move"
    );

    private static final WordTree WORD_TREE;

    static {
        WORD_TREE = new WordTree();
        WORD_TREE.addWords(BLACK_LIST);
    }

    @Override
    public final CodeSandboxResult executeCode(CodeSandboxRequest codeSandboxRequest) {
        List<String> inputList = codeSandboxRequest.getInputList();
        String code = codeSandboxRequest.getCode();

        // 0. 黑名单安全检测
        List<String> matched = WORD_TREE.matchAll(code);
        if (!matched.isEmpty()) {
            log.warn("dangerous code detected, matched keywords: {}", matched);
            return buildErrorResult("Dangerous Code", "code contains blacklisted keywords: " + matched);
        }

        // 1. 保存用户代码到文件
        File userCodeFile = saveCodeToFile(code);
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();

        try {
            // 2. 编译代码
            ExecuteMessage compileMessage = compileFile(userCodeFile);
            log.info("compile result: {}", compileMessage);
            if (compileMessage.getExitCode() == null || compileMessage.getExitCode() != 0) {
                return buildErrorResult("Compile Error", compileMessage.getErrorMessage());
            }

            // 3. 执行（子类钩子）
            List<ExecuteMessage> messages = runCode(userCodeFile, inputList);

            // 4. 整理结果
            return buildSuccessResult(messages);
        } catch (Exception e) {
            log.error("sandbox system error", e);
            return buildErrorResult("System Error", e.getMessage());
        } finally {
            // 5. 清理临时文件
            boolean deleted = FileUtil.del(userCodeParentPath);
            if (!deleted) {
                log.warn("failed to delete temp dir: {}", userCodeParentPath);
            }
        }
    }

    /** 子类钩子：执行已编译好的 class（位于 userCodeFile 同目录），逐个 input 产出 ExecuteMessage */
    protected abstract List<ExecuteMessage> runCode(File userCodeFile, List<String> inputList) throws Exception;

    /** 子类可覆盖以使用不同超时 */
    protected long getTimeout() {
        return DEFAULT_TIME_OUT;
    }

    private File saveCodeToFile(String code) {
        String userDir = System.getProperty("user.dir");
        String globalCodePath = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        if (!FileUtil.exist(globalCodePath)) {
            FileUtil.mkdir(globalCodePath);
        }
        String userCodeParentPath = globalCodePath + File.separator + UUID.randomUUID();
        String userCodePath = userCodeParentPath + File.separator + GLOBAL_JAVA_CLASS_NAME;
        return FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);
    }

    private ExecuteMessage compileFile(File userCodeFile) throws Exception {
        Process compileProcess = new ProcessBuilder(
                "javac", "-encoding", "utf-8", userCodeFile.getAbsolutePath())
                .start();
        return ProcessUtils.runProcessAndGetMessage(compileProcess, "compile");
    }

    private CodeSandboxResult buildSuccessResult(List<ExecuteMessage> executeMessageList) {
        List<String> outputList = new ArrayList<>();
        long maxTime = 0;
        long maxMemory = 0;
        for (ExecuteMessage msg : executeMessageList) {
            String errMsg = msg.getErrorMessage();
            if (StrUtil.isNotBlank(errMsg)) {
                if ("Time Limit Exceeded".equals(errMsg)) {
                    return buildTimeLimitResult();
                }
                return buildErrorResult("Runtime Error", errMsg);
            }
            outputList.add(msg.getMessage());
            if (msg.getTime() != null) {
                maxTime = Math.max(maxTime, msg.getTime());
            }
            if (msg.getMemory() != null) {
                maxMemory = Math.max(maxMemory, msg.getMemory());
            }
        }
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMessage("Accepted");
        judgeInfo.setTime(maxTime);
        judgeInfo.setMemory(maxMemory);

        CodeSandboxResult result = new CodeSandboxResult();
        result.setOutputList(outputList);
        result.setMessage("success");
        result.setJudgeInfo(judgeInfo);
        return result;
    }

    private CodeSandboxResult buildTimeLimitResult() {
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMessage("Time Limit Exceeded");
        judgeInfo.setTime(getTimeout());
        judgeInfo.setMemory(0L);

        CodeSandboxResult result = new CodeSandboxResult();
        result.setOutputList(new ArrayList<>());
        result.setMessage("Time Limit Exceeded");
        result.setJudgeInfo(judgeInfo);
        return result;
    }

    private CodeSandboxResult buildErrorResult(String judgeMessage, String detail) {
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMessage(judgeMessage);
        judgeInfo.setTime(0L);
        judgeInfo.setMemory(0L);

        CodeSandboxResult result = new CodeSandboxResult();
        result.setOutputList(new ArrayList<>());
        result.setMessage(detail);
        result.setJudgeInfo(judgeInfo);
        return result;
    }
}
