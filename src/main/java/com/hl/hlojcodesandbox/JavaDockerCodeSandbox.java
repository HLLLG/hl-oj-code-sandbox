package com.hl.hlojcodesandbox;

import cn.hutool.core.util.StrUtil;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.ExecCreateCmdResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.Statistics;
import com.github.dockerjava.api.model.StreamType;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import com.hl.hlojcodesandbox.model.ExecuteMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 使用 Docker 容器隔离运行用户代码 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "code-sandbox", name = "type", havingValue = "docker")
public class JavaDockerCodeSandbox extends JavaCodeSandboxTemplate {

    @Value("${code-sandbox.docker.host:tcp://localhost:2375}")
    private String dockerHost;

    @Value("${code-sandbox.docker.image}")
    private String image;

    @Value("${code-sandbox.docker.memory-mb:128}")
    private long memoryMb;

    @Value("${code-sandbox.docker.cpu-count:1}")
    private long cpuCount;

    private DockerClient dockerClient;

    @PostConstruct
    public void init() {
        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(dockerHost)
                .build();
        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .maxConnections(50)
                .connectionTimeout(Duration.ofSeconds(10))
                .responseTimeout(Duration.ofSeconds(60))
                .build();
        this.dockerClient = DockerClientImpl.getInstance(config, httpClient);

        ensureImage();
    }

    /** 镜像仅在本地缺失时拉取一次 */
    private void ensureImage() {
        try {
            dockerClient.inspectImageCmd(image).exec();
            log.info("docker image already exists: {}", image);
        } catch (NotFoundException e) {
            log.info("pulling docker image: {}", image);
            try {
                dockerClient.pullImageCmd(image)
                        .exec(new ResultCallback.Adapter<PullResponseItem>() {
                            @Override
                            public void onNext(PullResponseItem item) {
                                if (item.getStatus() != null) {
                                    log.info("pull: {}", item.getStatus());
                                }
                            }
                        })
                        .awaitCompletion();
                log.info("pull image done: {}", image);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("pull image interrupted", ie);
            }
        }
    }

    @PreDestroy
    public void close() {
        if (dockerClient != null) {
            try {
                dockerClient.close();
            } catch (Exception ignore) {
            }
        }
    }

    @Override
    protected List<ExecuteMessage> runCode(File userCodeFile, List<String> inputList) throws Exception {
        // 容器内的工作目录（避免与其它请求冲突）
        String workdirInContainer = "/app/" + UUID.randomUUID();

        // 1. 创建可交互、长生命周期的容器（资源 + 网络隔离）
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withMemory(memoryMb * 1024 * 1024)
                .withMemorySwap(0L)
                .withCpuCount(cpuCount)
                .withNetworkMode("none")
                .withReadonlyRootfs(false)
                .withSecurityOpts(List.of("no-new-privileges"));

        CreateContainerResponse container = dockerClient.createContainerCmd(image)
                .withHostConfig(hostConfig)
                .withName("hloj-sandbox-" + UUID.randomUUID())
                .withTty(false)
                .withAttachStdin(true)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .withStdinOpen(true)
                .withCmd("tail", "-f", "/dev/null")
                .exec();
        String containerId = container.getId();

        try {
            dockerClient.startContainerCmd(containerId).exec();

            // 2. 上传编译好的 class 文件到容器（远端 daemon 场景下绑定挂载不可行，改用 cp archive）
            // 先在容器里创建工作目录
            execAndWait(containerId, new String[]{"mkdir", "-p", workdirInContainer}, null, 5_000L);
            // 把宿主机 userCodeParentPath 下的所有内容（含 Main.class）拷到容器工作目录
            dockerClient.copyArchiveToContainerCmd(containerId)
                    .withHostResource(userCodeFile.getParentFile().getAbsolutePath())
                    .withRemotePath(workdirInContainer)
                    .exec();
            // copyArchiveToContainerCmd 会把整个目录一起带过去，因此 class 实际位于 <workdir>/<parentDirName>/Main.class
            String classpathInContainer = workdirInContainer + "/" + userCodeFile.getParentFile().getName();

            // 3. 逐用例执行（每次新建一个 exec）
            List<ExecuteMessage> messages = new ArrayList<>();
            for (String inputArgs : inputList) {
                String[] cmd = new String[]{
                        "java", "-Xmx" + memoryMb + "m", "-Dfile.encoding=UTF-8",
                        "-cp", classpathInContainer, "Main"
                };
                ExecuteMessage msg = execAndWait(containerId, cmd, inputArgs, getTimeout());
                log.info("docker execute result: {}", msg);
                messages.add(msg);
            }
            return messages;
        } finally {
            // 4. 清理容器
            try {
                dockerClient.removeContainerCmd(containerId).withForce(true).exec();
            } catch (Exception e) {
                log.warn("remove container failed: {}", containerId, e);
            }
        }
    }

    /**
     * 在容器中执行一条命令并采集输出/退出码/超时。
     * stdinArgs 非空时通过 exec stdin 写入并 EOF。
     */
    private ExecuteMessage execAndWait(String containerId, String[] cmd, String stdinArgs, long timeoutMs) {
        ExecuteMessage message = new ExecuteMessage();
        StringBuilder stdoutSb = new StringBuilder();
        StringBuilder stderrSb = new StringBuilder();
        AtomicLong peakMemoryKb = new AtomicLong(0L);

        // 启动 stats 流；首条样本几乎立刻送达，之后约 1s/条
        ResultCallback.Adapter<Statistics> statsCallback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Statistics stats) {
                Long usage = stats.getMemoryStats() == null ? null : stats.getMemoryStats().getUsage();
                if (usage != null) {
                    long kb = usage / 1024;
                    long prev;
                    do {
                        prev = peakMemoryKb.get();
                        if (kb <= prev) break;
                    } while (!peakMemoryKb.compareAndSet(prev, kb));
                }
            }
        };
        dockerClient.statsCmd(containerId).exec(statsCallback);

        ExecCreateCmdResponse execCreate = dockerClient.execCreateCmd(containerId)
                .withCmd(cmd)
                .withAttachStdin(stdinArgs != null)
                .withAttachStdout(true)
                .withAttachStderr(true)
                .exec();

        AtomicLong startNs = new AtomicLong();
        ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                String text = new String(frame.getPayload(), StandardCharsets.UTF_8);
                if (frame.getStreamType() == StreamType.STDERR) {
                    stderrSb.append(text);
                } else {
                    stdoutSb.append(text);
                }
            }
        };

        try (Closeable ignore = callback) {
            startNs.set(System.currentTimeMillis());
            var startCmd = dockerClient.execStartCmd(execCreate.getId()).withDetach(false);
            if (stdinArgs != null) {
                startCmd = startCmd.withStdIn(new java.io.ByteArrayInputStream(
                        (stdinArgs + "\n").getBytes(StandardCharsets.UTF_8)));
            }
            startCmd.exec(callback);

            boolean finished = callback.awaitCompletion(timeoutMs, TimeUnit.MILLISECONDS);
            long elapsed = System.currentTimeMillis() - startNs.get();

            if (!finished) {
                // 超时：杀掉容器，避免残留进程
                try {
                    dockerClient.killContainerCmd(containerId).exec();
                } catch (Exception ignored) {
                }
                message.setExitCode(-1);
                message.setErrorMessage("Time Limit Exceeded");
                message.setTime(timeoutMs);
                message.setMemory(peakMemoryKb.get());
                return message;
            }

            Long exitCode = dockerClient.inspectExecCmd(execCreate.getId()).exec().getExitCodeLong();
            message.setExitCode(exitCode == null ? -1 : exitCode.intValue());
            message.setTime(elapsed);
            message.setMessage(StrUtil.trim(stdoutSb.toString()));
            message.setErrorMessage(StrUtil.trim(stderrSb.toString()));
            message.setMemory(peakMemoryKb.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            message.setExitCode(-1);
            message.setErrorMessage("execution interrupted");
        } catch (Exception e) {
            log.error("docker exec error", e);
            message.setExitCode(-1);
            message.setErrorMessage(e.getMessage());
        } finally {
            try {
                statsCallback.close();
            } catch (Exception ignore) {
            }
        }
        return message;
    }
}
