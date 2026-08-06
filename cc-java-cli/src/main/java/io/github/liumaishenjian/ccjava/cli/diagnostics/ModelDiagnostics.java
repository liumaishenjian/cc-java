package io.github.liumaishenjian.ccjava.cli.diagnostics;

import io.github.liumaishenjian.ccjava.core.ModelDiagnosticRecorder;
import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticMode;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * CLI Composition Root 使用的模型诊断资源。
 *
 * <p>OFF 返回纯 no-op 且不解析、创建目录；其他模式才实例化异步本机 sink。
 * 目录只在此可信启动边界选择，不进入 Runtime 事件或协议。</p>
 *
 * @since 0.1.0
 */
public final class ModelDiagnostics implements AutoCloseable {

    private final ModelDiagnosticRecorder recorder;
    private final AutoCloseable resource;

    private ModelDiagnostics(ModelDiagnosticRecorder recorder, AutoCloseable resource) {
        this.recorder = recorder;
        this.resource = resource;
    }

    /**
     * 按 CLI 模式打开诊断资源。
     *
     * @param mode OFF、SAFE 或 VERBOSE
     * @param trustedDirectory 显式可信目录；空时使用用户私有默认目录
     * @return 可交给 Provider Adapter 并由 Session 关闭的资源
     */
    public static ModelDiagnostics open(ModelDiagnosticMode mode, Optional<Path> trustedDirectory) {
        Objects.requireNonNull(mode, "mode 不能为空");
        Objects.requireNonNull(trustedDirectory, "trustedDirectory 不能为空");
        if (mode == ModelDiagnosticMode.OFF) {
            return new ModelDiagnostics(ModelDiagnosticRecorder.off(), () -> { });
        }
        try {
            Path directory = trustedDirectory.orElseGet(() -> Path.of(
                    System.getProperty("user.home"), ".cc-java", "diagnostics"));
            JsonlModelDiagnosticSink sink = new JsonlModelDiagnosticSink(
                    directory.toAbsolutePath().normalize());
            return new ModelDiagnostics(new ModelDiagnosticRecorder(mode, sink), sink);
        } catch (RuntimeException ignored) {
            // 诊断是 best-effort 附属能力；构造、路径、权限或创建失败均静默降级。
            return new ModelDiagnostics(ModelDiagnosticRecorder.off(), () -> { });
        }
    }

    /** @return 仅接受封闭事件的 Core 记录器 */
    public ModelDiagnosticRecorder recorder() {
        return recorder;
    }

    @Override
    public void close() {
        try {
            resource.close();
        } catch (Exception ignored) {
            // best-effort 诊断关闭失败不得污染 stdout/stderr 或改变进程结果。
        }
    }
}
