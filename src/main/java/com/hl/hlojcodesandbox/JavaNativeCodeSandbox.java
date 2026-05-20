package com.hl.hlojcodesandbox;

import com.hl.hlojcodesandbox.model.ExecuteMessage;
import com.hl.hlojcodesandbox.utils.ProcessUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 使用宿主机 java 进程运行用户代码 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "code-sandbox", name = "type", havingValue = "native", matchIfMissing = true)
public class JavaNativeCodeSandbox extends JavaCodeSandboxTemplate {

    @Override
    protected List<ExecuteMessage> runCode(File userCodeFile, List<String> inputList) throws Exception {
        String userCodeParentPath = userCodeFile.getParentFile().getAbsolutePath();
        List<ExecuteMessage> messages = new ArrayList<>();
        for (String inputArgs : inputList) {
            Process runProcess = new ProcessBuilder(
                    "java", "-Xmx256m", "-Dfile.encoding=UTF-8", "-cp", userCodeParentPath, "Main")
                    .start();
            ExecuteMessage msg = ProcessUtils.runInteractProcessAndGetMessage(runProcess, inputArgs, getTimeout());
            log.info("execute result: {}", msg);
            messages.add(msg);
        }
        return messages;
    }
}
