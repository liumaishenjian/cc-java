package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.domain.ModelDiagnosticMode;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

import java.util.Locale;

/** 将可信 CLI 文本转换为封闭模型诊断模式。 */
final class ModelDiagnosticModeConverter implements ITypeConverter<ModelDiagnosticMode> {

    @Override
    public ModelDiagnosticMode convert(String value) {
        try {
            return ModelDiagnosticMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException failure) {
            throw new TypeConversionException("diagnostics 模式必须是 off、safe 或 verbose");
        }
    }
}
