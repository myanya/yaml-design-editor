package {#each config.package}{.}{#if more}.{/if}{/each}.web;

import jakarta.servlet.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

/** 確定した画面遷移設計の URL とハンドラ。入力保持と表示は共通処理へ渡す。 */
@Controller
public class {record.controller} {{
    private final WebRuntime runtime;
    public {record.controller}(WebRuntime runtime) {{this.runtime=runtime;}}
{#each record.routes}
    @RequestMapping(path={path:java-string},method=RequestMethod.{method})
    public String {handler}(HttpServletRequest request,HttpServletResponse response,Model model) {{
        return runtime.handle(request,response,model);
    }}
{/each}}}
