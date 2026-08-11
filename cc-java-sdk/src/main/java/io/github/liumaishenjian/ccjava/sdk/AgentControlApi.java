package io.github.liumaishenjian.ccjava.sdk;

import io.github.liumaishenjian.ccjava.core.session.RetentionAction;
import io.github.liumaishenjian.ccjava.core.session.SessionIndexEntry;
import java.util.List;

/**
 * SDK 与 stable protocol 共用的受控 Session/Governance 控制 API。
 *
 * <p>调用方只提交 Session identity、导出 policy 和确认；workspace、canonical records 与
 * lifecycle status 均由服务端事实源推导，不能由客户端声明。</p>
 *
 * @since 0.1.0
 */
public interface AgentControlApi {
    /**
     * 从服务端 canonical Session 导出稳定交换格式。
     *
     * @param sessionId 服务端 Session identity
     * @param includeContent 是否请求包含允许导出的正文
     * @param redacted 是否启用逐字段脱敏
     * @param confirmed 是否完成正文导出的显式确认
     * @return 稳定 UTF-8 导出字节
     */
    byte[] exportSession(String sessionId, boolean includeContent, boolean redacted, boolean confirmed);

    /**
     * 在实际 writer/recovery/migration fence 下执行 retention。
     *
     * @param sessionId 服务端 Session identity
     * @param action archive 或 delete
     * @param firstConfirmation 第一次明确确认
     * @param secondConfirmation 永久删除所需第二次确认
     * @return 不含路径或正文的终态
     */
    ControlResult retainSession(
            String sessionId,
            RetentionAction action,
            boolean firstConfirmation,
            boolean secondConfirmation);

    /**
     * 分页列出可重建 Session index。
     *
     * @param offset 零基偏移
     * @param limit 最大返回数
     * @return 稳定顺序的 index entries
     */
    List<SessionIndexEntry> listSessions(int offset, int limit);

    /**
     * 搜索可重建 Session index 的有界展示字段。
     *
     * @param query 有界查询文本
     * @param limit 最大返回数
     * @return 匹配的 index entries
     */
    List<SessionIndexEntry> searchSessions(String query, int limit);

    /**
     * 迁移调用方明确指定的本地 Session 交换文件。
     *
     * @param sourceFile 源文件
     * @param targetFile create-only 目标文件
     * @param fromMajor 源 schema major
     * @param toMajor 目标 schema major
     * @return 不含路径或正文的迁移终态
     */
    MigrationControlResult migrateSession(
            String sourceFile, String targetFile, int fromMajor, int toMajor);

    /**
     * 查询服务端实际装配的 Managed provenance/LKG 与 feature gates。
     *
     * @return 不含路径和策略正文的隐私安全投影
     */
    GovernanceView governance();

    /**
     * retention 操作的封闭结果。
     *
     * @param success 是否完成请求动作
     * @param status 固定状态码
     */
    record ControlResult(boolean success, String status) {
    }

    /**
     * 不暴露本机路径或 record 正文的 migration 终态。
     *
     * @param success 是否 create-only 发布成功
     * @param status 固定状态码
     * @param records 已迁移的完整记录数
     */
    record MigrationControlResult(boolean success, String status, int records) {
    }

    /**
     * Managed governance 的稳定只读投影。
     *
     * @param status current/LKG/absent/fail-closed 状态
     * @param usingLkg 是否使用 last-known-good
     * @param stableEnabled 启用的稳定 feature IDs
     * @param experimentalEnabled 启用的实验 feature IDs
     */
    record GovernanceView(
            String status,
            boolean usingLkg,
            List<String> stableEnabled,
            List<String> experimentalEnabled) {
        /** 防御性冻结 feature ID 列表。 */
        public GovernanceView {
            stableEnabled = List.copyOf(stableEnabled);
            experimentalEnabled = List.copyOf(experimentalEnabled);
        }
    }
}
