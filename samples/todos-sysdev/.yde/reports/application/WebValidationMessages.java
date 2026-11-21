package {#each config.package}{.}{#if more}.{/if}{/each}.web;

import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.support.AbstractMessageSource;
import org.springframework.stereotype.Component;

/** 入力の誤りの確定メッセージ。業務の断りは後続の画面遷移で接続する。 */
@Component("messageSource")
public class WebValidationMessages extends AbstractMessageSource {{
    private final Map<String, String> messages = new LinkedHashMap<>();

    public WebValidationMessages() {{
{#each record.validation}
{#each messages}
        messages.put({key:java-string}, {text:java-string});
{/each}
{/each}
    }

    @Override
    protected MessageFormat resolveCode(String code, Locale locale) {{
        String text = messages.get(code);
        return text == null ? null : new MessageFormat(text, locale);
    }
}
