package io.github.liumaishenjian.ccjava.cli.plugins;

import java.io.IOException;
import java.nio.file.Path;

/** staged install 的目录持久化端口；生产默认必须具备真实 directory flush。 */
@FunctionalInterface
interface DirectoryDurability {
    void force(Path directory) throws IOException;
}
