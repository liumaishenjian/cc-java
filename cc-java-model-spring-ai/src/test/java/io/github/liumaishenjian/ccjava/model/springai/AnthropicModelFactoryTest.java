package io.github.liumaishenjian.ccjava.model.springai;

import static org.assertj.core.api.Assertions.*; import io.github.liumaishenjian.ccjava.model.springai.config.*; import java.net.URI; import java.nio.file.Path; import java.util.Map; import org.junit.jupiter.api.Test; import org.junit.jupiter.api.io.TempDir;
class AnthropicModelFactoryTest { @TempDir Path temp;
 @Test void createsModelWithoutAutomaticRetryAndRedactsSettings(){AnthropicSettings settings=new AnthropicSettings(URI.create("https://api.anthropic.com"),"SECRET_SENTINEL","model");assertThat(new AnthropicModelFactory().create(settings)).isNotNull();assertThat(settings.toString()).doesNotContain("SECRET_SENTINEL");}
 @Test void optionalLoaderRequiresAllThreeValues(){AnthropicSettingsLoader loader=new AnthropicSettingsLoader();assertThat(loader.load(temp,Map.of())).isEmpty();assertThatThrownBy(()->loader.load(temp,Map.of(AnthropicSettingsLoader.API_KEY_ENV,"secret"))).isInstanceOf(ProviderConfigurationException.class);assertThat(loader.load(temp,Map.of(AnthropicSettingsLoader.BASE_URL_ENV,"https://api.anthropic.com",AnthropicSettingsLoader.API_KEY_ENV,"secret",AnthropicSettingsLoader.MODEL_ENV,"model"))).isPresent();}
}
