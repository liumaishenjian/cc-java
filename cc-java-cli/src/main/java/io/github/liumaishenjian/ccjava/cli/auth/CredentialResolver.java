package io.github.liumaishenjian.ccjava.cli.auth;

import io.github.liumaishenjian.ccjava.core.CancellationToken;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 确定性解析 profile，显式/default 层失效时绝不向下 fallback。 */
public final class CredentialResolver {
    private final CredentialStore store; private final Map<String,String> environment;

    /**
     * 使用凭据存储和固定的环境变量快照创建凭据解析器。
     *
     * @param store 用于读取凭据元数据和密钥的持久化存储
     * @param environment 用于解析环境变量类型配置和兜底凭据的环境变量快照
     */
    public CredentialResolver(CredentialStore store,Map<String,String> environment) {
        this.store=Objects.requireNonNull(store); this.environment=Map.copyOf(Objects.requireNonNull(environment));
    }

    /**
     * 按显式配置、Provider 默认配置、cc-java 环境变量的顺序解析单一凭据。
     *
     * @param providerId 待解析凭据的 Provider 标识
     * @param explicit 显式指定的配置标识；为空时依次尝试 Provider 默认配置和环境变量
     * @param cancellation 本次解析以及读取密钥时使用的取消信号
     * @return 持有单一密钥租约的已解析凭据
     * @throws ProviderAuthException 配置存在但无效，或所有凭据来源均缺失时
     */
    public ResolvedCredential resolve(String providerId,Optional<String> explicit,CancellationToken cancellation) {
        CredentialStore.Snapshot snapshot=store.snapshot(cancellation);
        if(explicit.isPresent()) return resolveStored(snapshot,providerId,explicit.orElseThrow(),cancellation);
        String defaultId=snapshot.providerDefaults().get(providerId);
        if(defaultId!=null) return resolveStored(snapshot,providerId,defaultId,cancellation);
        String envName=switch(providerId) {
            case "anthropic" -> "CC_JAVA_ANTHROPIC_API_KEY";
            case "openrouter" -> "CC_JAVA_OPENROUTER_API_KEY";
            default -> "CC_JAVA_OPENAI_API_KEY";
        };
        String value=environment.get(envName);
        if(value!=null&&!value.isBlank()) return new ResolvedCredential("env-ephemeral",0,new SecretMaterial(value.toCharArray()));
        throw failure(ProviderAuthException.Code.AUTH_PROFILE_REQUIRED,ProviderAuthException.Action.LOGIN);
    }
    private ResolvedCredential resolveStored(CredentialStore.Snapshot s,String provider,String id,CancellationToken c) {
        CredentialProfile p=s.find(provider,id).orElseThrow(()->failure(ProviderAuthException.Code.AUTH_PROFILE_UNKNOWN,ProviderAuthException.Action.SELECT_PROFILE));
        SecretMaterial value;
        if(p.secretRef() instanceof SecretRef.Store stored) value=store.readSecret(stored,c);
        else { String raw=environment.get(((SecretRef.Env)p.secretRef()).variableName()); if(raw==null||raw.isBlank()) throw failure(ProviderAuthException.Code.AUTH_SECRET_UNAVAILABLE,ProviderAuthException.Action.LOGIN); value=new SecretMaterial(raw.toCharArray()); }
        return new ResolvedCredential(id,s.generation(),value);
    }
    private static ProviderAuthException failure(ProviderAuthException.Code c,ProviderAuthException.Action a) { return new ProviderAuthException(c,a,false); }

    /**
     * 表示持有单一密钥租约的已解析凭据，关闭后会清零密钥。
     *
     * @param profileId 解析命中的配置标识
     * @param generation 解析时凭据索引的版本号；临时环境凭据为 {@code 0}
     * @param secret 由该凭据独占并在关闭时清零的密钥材料
     */
    public record ResolvedCredential(String profileId,long generation,SecretMaterial secret) implements AutoCloseable {
        /** 校验并取得 secret 所有权。 */ public ResolvedCredential { Objects.requireNonNull(profileId); Objects.requireNonNull(secret); }
        @Override public void close() { secret.close(); }
    }
}
