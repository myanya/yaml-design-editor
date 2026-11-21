package {#each config.package}{.}{#if more}.{/if}{/each}.web;

import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;
import org.springframework.web.server.ResponseStatusException;
import {#each config.package}{.}{#if more}.{/if}{/each}.service.error.NotFoundException;

/** 利用者には公開用の文面、ログには受付番号と原因を残す。 */
@ControllerAdvice
public class WebErrors {{
    private static final org.slf4j.Logger LOG=LoggerFactory.getLogger(WebErrors.class);
    private final WebCatalog catalog;
    public WebErrors(WebCatalog catalog){{this.catalog=catalog;}}
    @ExceptionHandler(NotFoundException.class)
    public ModelAndView missing(NotFoundException ex) {{
        LOG.info("対象なし: {{}} {{}}",ex.getEntity(),ex.getId());
        return error(404,catalog.message("error.notFound."+ex.getEntity(),Map.of("id",ex.getId())));
    }}
    @ExceptionHandler(ResponseStatusException.class)
    public ModelAndView status(ResponseStatusException ex) {{return error(ex.getStatusCode().value(),ex.getStatusCode().value()==404?"ページが見つかりません。":"この操作方法は使用できません。");}}
    @ExceptionHandler({{org.springframework.web.servlet.resource.NoResourceFoundException.class,org.springframework.web.HttpRequestMethodNotSupportedException.class}})
    public ModelAndView route(Exception ex){{return error(ex instanceof org.springframework.web.HttpRequestMethodNotSupportedException?405:404,"ページが見つからないか、この操作方法は使用できません。");}}
    @ExceptionHandler(Exception.class)
    public ModelAndView unexpected(Exception ex) {{
        String receipt=UUID.randomUUID().toString().substring(0,8);LOG.error("受付番号 {{}}",receipt,ex);
        return error(500,catalog.message("error.unexpected",Map.of("no",receipt)));
    }}
    private ModelAndView error(int status,String message) {{var result=new ModelAndView("error");result.setStatus(org.springframework.http.HttpStatus.valueOf(status));result.addObject("message",message);result.addObject("home",catalog.destinations.get(catalog.homeScreen()));return result;}}
}}
