package io.github.liumaishenjian.ccjava.cli;

import io.github.liumaishenjian.ccjava.cli.auth.ProviderAuthException;
import io.github.liumaishenjian.ccjava.cli.auth.SecretMaterial;
import io.github.liumaishenjian.ccjava.cli.provider.ProviderDefinition;
import io.github.liumaishenjian.ccjava.cli.runtime.ProviderAuthApplicationService;
import io.github.liumaishenjian.ccjava.core.CancellationToken;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.io.Console;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;

/** MODEL-13 Picocli 本地控制面命令集合。 */
final class ProviderControlCommands {
    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private ProviderControlCommands() { }

    /** 创建 providers 根命令。 */
    static Object providers(ProviderAuthApplicationService service, PrintWriter out, PrintWriter err) {
        return new Providers(service, out, err);
    }
    /** 创建 auth 根命令。 */
    static Object auth(ProviderAuthApplicationService service, InputStream input, PrintWriter out, PrintWriter err) {
        return new Auth(service, input, out, err);
    }
    /** 创建 models 根命令。 */
    static Object models(ProviderAuthApplicationService service, PrintWriter out, PrintWriter err) {
        return new Models(service, out, err);
    }

    @Command(mixinStandardHelpOptions = true,name = "providers", description = "管理本机非秘密 Provider definition",
            subcommands = {ProvidersList.class, ProvidersAdd.class, ProvidersRemove.class})
    static final class Providers implements Callable<Integer> {
        final ProviderAuthApplicationService service; final PrintWriter out; final PrintWriter err;
        Providers(ProviderAuthApplicationService service, PrintWriter out, PrintWriter err) {
            this.service=service; this.out=out; this.err=err;
        }
        @Override public Integer call() { return 2; }
    }

    @Command(mixinStandardHelpOptions = true,name = "list", description = "列出本机 Provider catalog")
    static final class ProvidersList implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Providers parent;
        @Option(names="--json") boolean json;
        @Override public Integer call() { return execute(parent.err, () -> {
            var values=parent.service.listProviders(CancellationToken.none());
            if(json) writeJson(parent.out, Map.of("version",1,"providers",values.stream().map(value -> {
                Map<String,Object> item=new LinkedHashMap<>(); item.put("providerId",value.providerId());
                item.put("kind",value.kind().name()); item.put("modelCount",value.modelCount());
                item.put("defaultModelId",value.defaultModelId()); item.put("selectedDefault",value.selectedDefault());
                return item; }).toList()));
            else values.forEach(value -> parent.out.println(value.providerId()+"\t"+value.kind()+"\tmodels="
                    +value.modelCount()+"\tdefault="+value.defaultModelId()));
            return 0;
        }); }
    }

    @Command(mixinStandardHelpOptions = true,name = "add", description = "新增 OpenAI-compatible Provider")
    static final class ProvidersAdd implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Providers parent;
        @Option(names="--id",required=true) String id;
        @Option(names="--kind",required=true) String kind;
        @Option(names="--display-name") String displayName;
        @Option(names="--base-url",required=true) String baseUrl;
        @Option(names="--model",required=true,arity="1..*") List<String> models;
        @Option(names="--default-model") String defaultModel;
        @Option(names="--connect-timeout-seconds",defaultValue="10") long connect;
        @Option(names="--request-timeout-seconds",defaultValue="300") long request;
        @Override public Integer call() { return execute(parent.err, () -> {
            if(!"openai-compatible".equals(kind)) throw new IllegalArgumentException("kind");
            ProviderDefinition definition=new ProviderDefinition(id,ProviderDefinition.Kind.OPENAI_COMPATIBLE,
                    displayName==null?id:displayName,URI.create(baseUrl),
                    ProviderDefinition.ApiVariant.OPENAI_CHAT_COMPLETIONS,models,
                    defaultModel==null?models.getFirst():defaultModel,Map.of(),
                    Duration.ofSeconds(connect),Duration.ofSeconds(request));
            parent.service.addProvider(definition,CancellationToken.none());
            parent.out.println("provider saved: "+id); return 0;
        }); }
    }

    @Command(mixinStandardHelpOptions = true,name = "remove", description = "删除 custom Provider")
    static final class ProvidersRemove implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Providers parent;
        @Option(names="--id",required=true) String id;
        @Option(names="--yes",required=true) boolean yes;
        @Override public Integer call() { return execute(parent.err, () -> {
            if(!yes) throw new IllegalArgumentException("confirmation");
            parent.service.removeProvider(id,CancellationToken.none());
            parent.out.println("provider removed: "+id); return 0;
        }); }
    }

    @Command(mixinStandardHelpOptions = true,name="auth",description="管理本机 Provider credential",
            subcommands={AuthLogin.class,AuthList.class,AuthStatus.class,AuthProbe.class,AuthLogout.class,AuthMigrate.class})
    static final class Auth implements Callable<Integer> {
        final ProviderAuthApplicationService service; final InputStream input; final PrintWriter out; final PrintWriter err;
        Auth(ProviderAuthApplicationService service,InputStream input,PrintWriter out,PrintWriter err){
            this.service=service;this.input=input;this.out=out;this.err=err;
        }
        @Override public Integer call(){return 2;}
    }

    @Command(mixinStandardHelpOptions = true,name="login",description="创建或替换 credential profile")
    static final class AuthLogin implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Auth parent;
        @Option(names="--provider",required=true) String provider;
        @Option(names="--profile",required=true) String profile;
        @Option(names="--api-key-stdin") boolean stdin;
        @Option(names="--from-env") String environmentName;
        @Option(names="--set-default") boolean setDefault;
        @Override public Integer call(){return execute(parent.err,()->{
            if(stdin&&environmentName!=null) throw new IllegalArgumentException("secret source");
            ProviderAuthApplicationService.LoginRequest request;
            ProviderAuthApplicationService.SecretInput input=null;
            if(environmentName!=null){
                request=new ProviderAuthApplicationService.LoginRequest(provider,profile,
                        ProviderAuthApplicationService.RefKind.ENV,environmentName,setDefault);
            }else{
                request=new ProviderAuthApplicationService.LoginRequest(provider,profile,
                        ProviderAuthApplicationService.RefKind.STORE,null,setDefault);
                input=stdin?()->readStdinSecret(parent.input):consoleInput();
            }
            var saved=parent.service.login(request,input,CancellationToken.none());
            parent.out.println("profile saved: "+saved.providerId()+"/"+saved.profileId());return 0;
        });}
    }

    @Command(mixinStandardHelpOptions = true,name="list",description="列出本机 credential metadata")
    static final class AuthList implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Auth parent;
        @Option(names="--provider") String provider;
        @Option(names="--json") boolean json;
        @Override public Integer call(){return execute(parent.err,()->{
            var values=parent.service.listProfiles(Optional.ofNullable(provider),CancellationToken.none());
            if(json) writeJson(parent.out,Map.of("version",1,"profiles",values.stream().map(
                    ProviderControlCommands::profileJson).toList()));
            else values.forEach(value->parent.out.println(value.providerId()+"\t"+value.profileId()+"\t"
                    +value.authMethod()+"\t"+value.refKind()+"\t"+value.status()
                    +(value.providerDefault()?"\tdefault":"")));
            return 0;
        });}
    }

    @Command(mixinStandardHelpOptions = true,name="status",description="读取单个 credential 本机状态")
    static final class AuthStatus implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Auth parent;
        @Option(names="--provider",required=true) String provider;
        @Option(names="--profile",required=true) String profile;
        @Option(names="--json") boolean json;
        @Override public Integer call(){return execute(parent.err,()->{
            var value=parent.service.status(provider,profile,CancellationToken.none());
            if(json)writeJson(parent.out,Map.of("version",1,"profile",profileJson(value)));
            else parent.out.println(value.providerId()+"/"+value.profileId()+"\t"+value.status());return 0;
        });}
    }

    @Command(mixinStandardHelpOptions = true,name="probe",description="显式验证 Provider credential（Batch C）")
    static final class AuthProbe implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Auth parent;
        @Option(names="--provider",required=true) String provider;
        @Option(names="--profile",required=true) String profile;
        @Option(names="--model") String model;
        @Option(names="--timeout-seconds",defaultValue="5") long timeoutSeconds;
        @Option(names="--json") boolean json;
        @Override public Integer call(){return execute(parent.err,()->{
            String selectedModel=model;
            if(selectedModel==null){
                var models=parent.service.listModels(Optional.of(provider),CancellationToken.none());
                selectedModel=models.stream().filter(ProviderAuthApplicationService.ModelSummary::providerDefault)
                        .findFirst().orElseThrow(()->new IllegalArgumentException("model")).modelId();
            }
            var result=parent.service.probe(new ProviderAuthApplicationService.ProbeRequest(provider,profile,
                    selectedModel,Duration.ofSeconds(timeoutSeconds)),CancellationToken.none());
            if(json)writeJson(parent.out,Map.of("version",1,"providerId",result.providerId(),
                    "profileId",result.profileId(),"modelId",result.modelId(),
                    "status",result.outcome().name(),"probedAt",result.probedAt().toString()));
            else parent.out.println(result.providerId()+"/"+result.profileId()+"\t"+result.outcome());
            return 0;
        });}
    }

    @Command(mixinStandardHelpOptions = true,name="logout",description="删除本机 credential；不会远端 revoke")
    static final class AuthLogout implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Auth parent;
        @Option(names="--provider",required=true) String provider;
        @Option(names="--profile",required=true) String profile;
        @Option(names="--yes",required=true) boolean yes;
        @Override public Integer call(){return execute(parent.err,()->{
            if(!yes)throw new IllegalArgumentException("confirmation");
            parent.service.logout(provider,profile,CancellationToken.none());
            parent.out.println("local credential deleted: "+provider+"/"+profile);
            parent.out.println("Provider-side credential was not revoked; rotate or delete it in the Provider console.");
            return 0;
        });}
    }

    @Command(mixinStandardHelpOptions = true,name="migrate-legacy",description="显式复制固定 legacy properties")
    static final class AuthMigrate implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Auth parent;
        @Option(names="--provider",required=true) String provider;
        @Option(names="--profile",required=true) String profile;
        @Option(names="--set-default") boolean setDefault;
        @Override public Integer call(){return execute(parent.err,()->{
            var result=parent.service.migrateLegacy(provider,profile,setDefault,CancellationToken.none());
            parent.out.println(result.code()+": "+provider+"/"+profile);return 0;
        });}
    }

    @Command(mixinStandardHelpOptions = true,name="models",description="列出、维护并选择本地模型",
            subcommands={ModelsList.class,ModelsAdd.class,ModelsRemove.class,ModelsUse.class})
    static final class Models implements Callable<Integer> {
        final ProviderAuthApplicationService service;final PrintWriter out;final PrintWriter err;
        Models(ProviderAuthApplicationService service,PrintWriter out,PrintWriter err){this.service=service;this.out=out;this.err=err;}
        @Override public Integer call(){return 2;}
    }

    @Command(mixinStandardHelpOptions = true,name="list",description="列出本地 catalog 模型")
    static final class ModelsList implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Models parent;
        @Option(names="--provider")String provider;
        @Option(names="--json")boolean json;
        @Override public Integer call(){return execute(parent.err,()->{
            var values=parent.service.listModels(Optional.ofNullable(provider),CancellationToken.none());
            if(json)writeJson(parent.out,Map.of("version",1,"models",values));
            else values.forEach(value->parent.out.println(value.providerId()+"\t"+value.modelId()
                    +(value.providerDefault()?"\tprovider-default":"")));return 0;
        });}
    }

    @Command(mixinStandardHelpOptions = true,name="add",description="给 built-in Provider 增加模型 overlay")
    static final class ModelsAdd implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Models parent;
        @Option(names="--provider",required=true)String provider;
        @Option(names="--model",required=true)String model;
        @Option(names="--set-default")boolean setDefault;
        @Override public Integer call(){return execute(parent.err,()->{
            parent.service.addModel(provider,model,setDefault,CancellationToken.none());
            parent.out.println("model added: "+provider+"/"+model);return 0;
        });}
    }

    @Command(mixinStandardHelpOptions = true,name="remove",description="从 built-in Provider 隐藏模型 overlay")
    static final class ModelsRemove implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Models parent;
        @Option(names="--provider",required=true)String provider;
        @Option(names="--model",required=true)String model;
        @Override public Integer call(){return execute(parent.err,()->{
            parent.service.removeModel(provider,model,CancellationToken.none());
            parent.out.println("model removed: "+provider+"/"+model);return 0;
        });}
    }

    @Command(mixinStandardHelpOptions = true,name="use",description="持久化默认 provider/model；可同时指定下一 Run profile")
    static final class ModelsUse implements Callable<Integer> {
        @picocli.CommandLine.ParentCommand Models parent;
        @Option(names="--provider",required=true)String provider;
        @Option(names="--model",required=true)String model;
        @Option(names="--profile")String profile;
        @Option(names="--session-only",description="只影响当前进程下一 Run，不写入用户默认")boolean sessionOnly;
        @Override public Integer call(){return execute(parent.err,()->{
            var value=parent.service.selectModel(new ProviderAuthApplicationService.ModelSelectionRequest(
                    provider,model,Optional.ofNullable(profile),!sessionOnly),CancellationToken.none());
            parent.out.println("next run model: "+value.providerId()+"/"+value.modelId());return 0;
        });}
    }

    private static Map<String,Object> profileJson(ProviderAuthApplicationService.ProfileSummary value){
        Map<String,Object> item=new LinkedHashMap<>();item.put("providerId",value.providerId());
        item.put("profileId",value.profileId());item.put("authMethod",value.authMethod());
        item.put("refKind",value.refKind());item.put("localStatus",value.status().name());
        item.put("default",value.providerDefault());value.lastProbeCode().ifPresent(v->item.put("lastProbeCode",v));
        value.lastProbeAt().ifPresent(v->item.put("lastProbeAt",v.toString()));return item;
    }

    private static ProviderAuthApplicationService.SecretInput consoleInput(){
        Console console=System.console();
        if(console==null)throw new ProviderAuthException(ProviderAuthException.Code.AUTH_SECRET_INPUT_REQUIRED,
                ProviderAuthException.Action.LOGIN,false);
        return consoleInput(() -> console.readPassword("API key: "));
    }

    static ProviderAuthApplicationService.SecretInput consoleInput(PasswordReader reader) {
        java.util.Objects.requireNonNull(reader, "reader 不能为空");
        return () -> {
            char[] value = reader.readPassword();
            if (value == null) {
                throw new ProviderAuthException(
                        ProviderAuthException.Code.AUTH_SECRET_INPUT_REQUIRED,
                        ProviderAuthException.Action.LOGIN,
                        false);
            }
            try {
                return new SecretMaterial(value);
            } finally {
                java.util.Arrays.fill(value, '\0');
            }
        };
    }

    @FunctionalInterface
    interface PasswordReader {
        char[] readPassword();
    }

    static SecretMaterial readStdinSecret(InputStream input){
        Objects.requireNonNull(input);byte[] bytes;
        try{ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] buffer=new byte[1024];
            int read;while((read=input.read(buffer))>=0){if(output.size()+read>16*1024)throw new IllegalArgumentException("stdin too large");output.write(buffer,0,read);}bytes=output.toByteArray();}
        catch(java.io.IOException failure){throw new IllegalArgumentException("stdin read");}
        try{int length=bytes.length;if(length>0&&bytes[length-1]=='\n'){length--;if(length>0&&bytes[length-1]=='\r')length--;}
            var decoder=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            char[] chars=decoder.decode(ByteBuffer.wrap(bytes,0,length)).toString().toCharArray();
            try{return new SecretMaterial(chars);}finally{java.util.Arrays.fill(chars,'\0');}}
        catch(java.nio.charset.CharacterCodingException invalid){throw new IllegalArgumentException("stdin utf8");}
        finally{java.util.Arrays.fill(bytes,(byte)0);}
    }

    private static int execute(PrintWriter err,Action action){
        try{return action.run();}
        catch(ProviderAuthException failure){err.println("cc-java: "+failure.code());return switch(failure.code()){
            case PROVIDER_UNKNOWN,MODEL_UNKNOWN,AUTH_PROFILE_UNKNOWN,AUTH_PROFILE_REQUIRED,
                 AUTH_SECRET_UNAVAILABLE,LEGACY_CONFIGURATION_INCOMPLETE->3;
            case AUTH_STORE_INSECURE,AUTH_STORE_LOCKED,AUTH_STORE_CORRUPT,AUTH_TRANSACTION_CONFLICT,
                 AUTH_STORE_DELETE_FAILED->4;
            case AUTH_PROBE_REJECTED,AUTH_PROBE_RATE_LIMITED,AUTH_PROBE_UNSUPPORTED,
                 AUTH_PROBE_UNREACHABLE->5;
            case AUTH_PROBE_TIMED_OUT,AUTH_CANCELLED->6;
            case AUTH_LOGOUT_DRAIN_FAILED->7;
            default->2;};}
        catch(IllegalArgumentException failure){err.println("cc-java: invalid provider/auth input");return 2;}
        catch(RuntimeException failure){err.println("cc-java: provider/auth operation failed");return 4;}
    }
    private static void writeJson(PrintWriter out,Object value){out.println(JSON.writeValueAsString(value));}
    @FunctionalInterface private interface Action{int run();}
}
