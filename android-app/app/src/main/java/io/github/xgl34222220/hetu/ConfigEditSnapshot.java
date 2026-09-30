package io.github.xgl34222220.hetu;

import java.io.IOException;
import java.util.Objects;

/** An in-memory editor baseline; source contents must never be logged or exported. */
final class ConfigEditSnapshot {
    final String coreId;
    final String name;
    final String originalText;

    ConfigEditSnapshot(String coreId, String name, String originalText) {
        this.coreId = Objects.requireNonNull(coreId);
        this.name = Objects.requireNonNull(name);
        this.originalText = Objects.requireNonNull(originalText);
    }

    void requireUnchanged(String currentCore, String currentName, String currentText) throws IOException {
        if (!coreId.equals(currentCore) || !name.equals(currentName)) {
            throw new IOException("当前配置已切换，未保存任何文件。请返回后重新打开要编辑的配置。");
        }
        if (!originalText.equals(currentText)) {
            throw new IOException("配置已在其他页面更新，未覆盖原文件。请保留修改并重新打开配置。");
        }
    }
}
