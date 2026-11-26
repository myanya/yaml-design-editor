package {#each config.package}{.}{#if more}.{/if}{/each}.web;

import java.util.*;
import java.time.*;
import java.security.SecureRandom;
import java.beans.PropertyEditorSupport;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.ui.Model;
import org.springframework.web.bind.ServletRequestDataBinder;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.util.UriTemplate;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import {#each config.package}{.}{#if more}.{/if}{/each}.service.error.*;
import static {#each config.package}{.}{#if more}.{/if}{/each}.web.WebCatalog.*;

/** HTTP の入力保持・検査・トークン・PRG。業務の判断と更新は Service が行う。 */
@Component
public class WebRuntime {{
    private final WebCatalog catalog;
    private final WebReads reads;
    private final WebActions actions;
    private final LocalValidatorFactoryBean validator;
    private final Clock clock;
    private final SecureRandom random=new SecureRandom();
    public WebRuntime(WebCatalog catalog,WebReads reads,WebActions actions,LocalValidatorFactoryBean validator,Clock clock) {{
        this.catalog=catalog;this.reads=reads;this.actions=actions;this.validator=validator;this.clock=clock;
    }}
    record Token(String secret,String action,String id) implements java.io.Serializable {{}}

    public String handle(HttpServletRequest request,HttpServletResponse response,Model model) {{
        String path=request.getRequestURI().substring(request.getContextPath().length());
        Map<String,Object> route=null;Map<String,String> variables=Map.of();boolean knownPath=false;
        for(var r:catalog.routes) {{
            String spec=s(r,"request");int split=spec.indexOf(' ');
            var template=new UriTemplate(spec.substring(split+1));
            if(template.matches(path)) {{knownPath=true;if(spec.substring(0,split).equals(request.getMethod())){{route=r;variables=template.match(path);break;}}}}
        }}
        if(route==null)throw new ResponseStatusException(knownPath?HttpStatus.METHOD_NOT_ALLOWED:HttpStatus.NOT_FOUND);
        String action=s(route,"action"),stage=s(route,"stage"),id=variables.getOrDefault("id","");
        var page=new WebPage(catalog,reads,request,LocalDate.now(clock));
        page.receiverId=id;page.action=action;
        if(action.isEmpty()) {{
            page.setScreen(s(route,"screen"));
            if(!id.isEmpty())page.loadEntity(s(route,"screen"),id);
            else page.loadDashboard(s(route,"service"));
            synchronized(request.getSession()) {{page.notice=Objects.toString(request.getSession().getAttribute(catalog.sessionKey("notice")),"");request.getSession().removeAttribute(catalog.sessionKey("notice"));}}
            model.addAttribute("page",page);return page.template();
        }}
        var operation=actions.actions.get(action);
        if(operation==null)throw new IllegalStateException("操作の対応がありません: "+action);
        page.operation=catalog.operations.get(action);
        var input=catalog.inputs.get(action);page.input=input;
        if(!id.isEmpty())page.loadEntity(s(input,"target"),id);
        page.from=page.allowedFrom(request.getParameter("from"));
        page.originId=page.originId(request.getParameter("origin"));
        Object form=operation.form().get();page.form=form;
        page.formDef=catalog.forms.getOrDefault(s(page.operation,"formClass"),Map.of());
        String screen=action+(stage.equals("execute")?"/confirm":s(route,"screen").endsWith("/confirm")?"/confirm":"/input");
        page.setScreen(screen);
        ServletRequestDataBinder binder=null;
        if(form!=null) {{
            binder=new ServletRequestDataBinder(form,s(page.formDef,"beanName"));
            binder.setAutoGrowCollectionLimit(200);binder.setIgnoreUnknownFields(false);
            binder.setAllowedFields(page.allowedFields().toArray(String[]::new));
            binder.registerCustomEditor(LocalDate.class,new PropertyEditorSupport(){{@Override public void setAsText(String text){{setValue(text==null||text.isEmpty()?null:LocalDate.parse(text));}}}});
            if(stage.equals("input")) binder.bind(new MutablePropertyValues(page.initialValues()));
            else binder.bind(request);
            // 入力画面の無い操作は確認画面から始まる。画面が運ぶ値（読んだときの版）は、確認画面を出すときに受け手から読む。
            if(stage.equals("confirm")&&!catalog.pages.containsKey(action+"/input")) binder.bind(new MutablePropertyValues(page.initialValues()));
            page.restoreCollections();
            if(!stage.equals("input")&&!stage.equals("back")&&request.getParameter("_rows")==null) {{binder.setValidator(validator);binder.validate();}}
            page.binding=binder.getBindingResult();
        }}
        // 確定の前に必ず消費する。照合と削除をセッション単位で一度に行う。
        if(stage.equals("execute")&&!consume(request,action,id)) {{
            request.getSession().setAttribute(catalog.sessionKey("notice"),catalog.message("error.token",Map.of()));
            return "redirect:"+page.returnUrl();
        }}
        // 入力画面の無い操作（行のボタンが番号だけを運ぶ操作）は、入力へ戻さない。
        boolean hasInput=catalog.pages.containsKey(action+"/input");
        // 番号で指す行は、画面を出す前に確かめる（無ければ見つからない扱い）。
        if(!stage.equals("input"))page.selectedRow();
        if(request.getParameter("_rows")!=null&&form!=null&&!stage.equals("execute")) {{
            page.editRows(request.getParameter("_rows"));page.setScreen(action+"/input");
        }} else if(page.binding!=null&&page.binding.hasErrors()) {{
            if(!hasInput)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
            page.setScreen(action+"/input");
        }} else if(stage.equals("confirm")) {{
            var violations=operation.check().apply(id,form);
            for(var v:violations)page.violation(v);
            if(!violations.isEmpty()&&form!=null&&hasInput)page.setScreen(action+"/input");
        }} else if(stage.equals("execute")) {{
            try {{
                String changed=operation.execute().apply(id,form);
                request.getSession().setAttribute(catalog.sessionKey("notice"),catalog.message(action+".done",Map.of("id",changed)));
                return "redirect:"+page.returnUrl();
            }} catch(RefusedException refused) {{
                var v=refused.getViolation();page.errors.add(catalog.message(v.key(),v.values()));
            }}
        }}
        if(page.screenKey.endsWith("/confirm"))issue(request,page,action,id);
        model.addAttribute("page",page);return page.template()+("true".equals(request.getHeader("HX-Request"))&&request.getParameter("_rows")!=null?" :: main":"");
    }}

    private void issue(HttpServletRequest request,WebPage page,String action,String id) {{
        var session=request.getSession();
        byte[] value=new byte[24];random.nextBytes(value);
        page.flow=UUID.randomUUID().toString();page.token=Base64.getUrlEncoder().withoutPadding().encodeToString(value);
        synchronized(session) {{
            var tokens=tokens(session);while(tokens.size()>=100)tokens.remove(tokens.keySet().iterator().next());
            tokens.put(page.flow,new Token(page.token,action,id));session.setAttribute(catalog.sessionKey("token"),tokens);
        }}
    }}
    private boolean consume(HttpServletRequest request,String action,String id) {{
        var session=request.getSession();
        synchronized(session) {{
            Token token=tokens(session).remove(request.getParameter("flow"));
            String supplied=request.getParameter("token");
            return token!=null&&token.action().equals(action)&&token.id().equals(id)&&supplied!=null&&java.security.MessageDigest.isEqual(token.secret().getBytes(java.nio.charset.StandardCharsets.UTF_8),supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }}
    }}
    @SuppressWarnings("unchecked") private Map<String,Token> tokens(HttpSession session) {{
        Object stored=session.getAttribute(catalog.sessionKey("token"));
        if(stored instanceof Map<?,?>)return (Map<String,Token>)stored;
        var tokens=new LinkedHashMap<String,Token>();session.setAttribute(catalog.sessionKey("token"),tokens);return tokens;
    }}
}}
