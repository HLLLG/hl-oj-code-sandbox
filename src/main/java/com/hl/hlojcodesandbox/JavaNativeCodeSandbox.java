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
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
public class JavaNativeCodeSandbox implements CodeSandbox {

    private static final String GLOBAL_CODE_DIR_NAME = "tmpCode";
    private static final String GLOBAL_JAVA_CLASS_NAME = "Main.java";
    private static final long TIME_OUT = 5000L;

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
    public CodeSandboxResult executeCode(CodeSandboxRequest codeSandboxRequest) {
        List<String> inputList = codeSandboxRequest.getInputList();
        String code = codeSandboxRequest.getCode();

        // 0. 黑名单安全检测
        List<String> matchWords = WORD_TREE.matchAll(code);
        if (!matchWords.isEmpty()) {
            log.warn("dangerous code detected, matched keywords: {}", matchWords);
            return buildErrorResult("Dangerous Code", "code contains blacklisted keywords: " + matchWords);
        }

        // 1. 保存用户代码到文件
        String userDir = System.getProperty("user.dir");
        String globalCodePath = userDir + File.separator + GLOBAL_CODE_DIR_NAME;
        if (!FileUtil.exist(globalCodePath)) {
            FileUtil.mkdir(globalCodePath);
        }
        String userCodeParentPath = globalCodePath + File.separator + UUID.randomUUID();
        String userCodePath = userCodeParentPath + File.separator + GLOBAL_JAVA_CLASS_NAME;
        File userCodeFile = FileUtil.writeString(code, userCodePath, StandardCharsets.UTF_8);

        try {
            // 2. 编译代码
            Process compileProcess = new ProcessBuilder(
                    "javac", "-encoding", "utf-8", userCodeFile.getAbsolutePath())
                    .start();
            ExecuteMessage compileMessage = ProcessUtils.runProcessAndGetMessage(compileProcess, "compile");
            log.info("compile result: {}", compileMessage);

            if (compileMessage.getExitCode() != 0) {
                return buildErrorResult("Compile Error", compileMessage.getErrorMessage());
            }

            // 3. 逐用例执行
            List<ExecuteMessage> executeMessageList = new ArrayList<>();
            for (String inputArgs : inputList) {
                Process runProcess = new ProcessBuilder(
                        "java", "-Xmx256m", "-Dfile.encoding=UTF-8", "-cp", userCodeParentPath, "Main")
                        .start();
                ExecuteMessage executeMessage = ProcessUtils.runInteractProcessAndGetMessage(
                        runProcess, inputArgs, TIME_OUT);
                log.info("execute result: {}", executeMessage);
                executeMessageList.add(executeMessage);
            }

            // 4. 整理输出结果
            return buildSuccessResult(executeMessageList);

        } catch (Exception e) {
            log.error("sandbox system error", e);
            return buildErrorResult("System Error", e.getMessage());
        } finally {
            // 5. 清理临时文件（保证执行）
            boolean deleted = FileUtil.del(userCodeParentPath);
            if (!deleted) {
                log.warn("failed to delete temp dir: {}", userCodeParentPath);
            }
        }
    }

    private CodeSandboxResult buildSuccessResult(List<ExecuteMessage> executeMessageList) {
        List<String> outputList = new ArrayList<>();
        long maxTime = 0;

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
        }

        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMessage("Accepted");
        judgeInfo.setTime(maxTime);
        judgeInfo.setMemory(0L);

        CodeSandboxResult result = new CodeSandboxResult();
        result.setOutputList(outputList);
        result.setMessage("success");
        result.setJudgeInfo(judgeInfo);
        return result;
    }

    private CodeSandboxResult buildTimeLimitResult() {
        JudgeInfo judgeInfo = new JudgeInfo();
        judgeInfo.setMessage("Time Limit Exceeded");
        judgeInfo.setTime(TIME_OUT);
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
