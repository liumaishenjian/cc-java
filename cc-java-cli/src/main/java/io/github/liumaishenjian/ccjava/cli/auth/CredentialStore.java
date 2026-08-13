package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 用户级 credential metadata/secret 的事务边界。
 *
 * <p>实现必须先发布 generation/index 再清理旧 secret，并对 lock、链接和权限不确定性 fail closed。</p>
 */
public interface CredentialStore {
    /**
     * 返回不读取凭据值的不可变本机快照。
     *
     * @param cancellation 本次读取操作的取消信号
     * @return 凭据索引的不可变快照
     */
    Snapshot snapshot(CancellationToken cancellation);
    /**
     * 原子创建或替换存储型配置，并消费凭据。
     *
     * @param providerId 提供方标识
     * @param profileId 配置标识
     * @param secret 待持久化且由本方法消费的凭据材料
     * @param setDefault 是否将该配置设为提供方的默认配置
     * @param cancellation 本次写入操作的取消信号
     * @return 创建或替换后的配置元数据
     */
    CredentialProfile saveStore(String providerId,String profileId,SecretMaterial secret,boolean setDefault,CancellationToken cancellation);
    /**
     * 原子创建或替换环境变量型配置；只保存引用。
     *
     * @param providerId 提供方标识
     * @param profileId 配置标识
     * @param envName 保存凭据值的环境变量名
     * @param setDefault 是否将该配置设为提供方的默认配置
     * @param cancellation 本次写入操作的取消信号
     * @return 创建或替换后的配置元数据
     */
    CredentialProfile saveEnv(String providerId,String profileId,String envName,boolean setDefault,CancellationToken cancellation);

    /**
     * 在不读取凭据值的情况下确认存储型凭据文件是否安全存在。
     *
     * @param ref 待检查的存储型凭据引用
     * @param cancellation 本次检查操作的取消信号
     * @return 凭据文件安全存在时为 {@code true}，否则为 {@code false}
     */
    boolean secretExists(SecretRef.Store ref,CancellationToken cancellation);

    /**
     * 读取单个存储型凭据；调用方负责关闭。
     *
     * @param ref 待读取的存储型凭据引用
     * @param cancellation 本次读取操作的取消信号
     * @return 由调用方负责关闭的凭据材料
     */
    SecretMaterial readSecret(SecretRef.Store ref,CancellationToken cancellation);
    /**
     * 注销排空完成后，原子删除配置及其凭据。
     *
     * @param providerId 提供方标识
     * @param profileId 待删除的配置标识
     * @param expectedGeneration 调用方预期的凭据索引代次
     * @param cancellation 本次删除操作的取消信号
     */
    void delete(String providerId,String profileId,long expectedGeneration,CancellationToken cancellation);
    /**
     * 原子更新不泄露隐私的最近探测元数据；不改变凭据引用或默认配置。
     *
     * @param providerId 提供方标识
     * @param profileId 待更新的配置标识
     * @param probe 待保存的不泄露隐私的探测摘要
     * @param expectedSecretRef 调用方预期的凭据引用，用于拒绝并发替换
     * @param cancellation 本次更新操作的取消信号
     * @return 更新后的配置元数据
     */
    default CredentialProfile saveProbe(String providerId,String profileId,CredentialProfile.ProbeRecord probe,
                                        SecretRef expectedSecretRef,CancellationToken cancellation) {
        throw new ProviderAuthException(ProviderAuthException.Code.AUTH_STORE_CORRUPT,
                ProviderAuthException.Action.CHECK_LOCAL_STORE, false);
    }

    /**
     * 不含凭据值的索引快照。
     *
     * @param generation 凭据索引的单调递增代次
     * @param profiles 快照中的配置元数据列表
     * @param providerDefaults 提供方标识到默认配置标识的映射
     */
    record Snapshot(long generation,List<CredentialProfile> profiles,Map<String,String> providerDefaults) {
        /** 防御性复制集合。 */ public Snapshot { profiles=List.copyOf(profiles); providerDefaults=Map.copyOf(providerDefaults); }
        /**
         * 精确查找配置。
         *
         * @param provider 提供方标识
         * @param profile 配置标识
         * @return 匹配的配置元数据；不存在时为空
         */
        public Optional<CredentialProfile> find(String provider,String profile) {
            return profiles.stream().filter(p->p.providerId().equals(provider)&&p.profileId().equals(profile)).findFirst();
        }
    }
}
