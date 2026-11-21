package {#each config.package}{.}{#if more}.{/if}{/each}.web;

import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.beans.BeanWrapperImpl;
import {#each config.package}{.}{#if more}.{/if}{/each}.data.mapper.*;
import {#each config.package}{.}{#if more}.{/if}{/each}.data.model.*;
import {#each config.package}{.}{#if more}.{/if}{/each}.service.*;
import static {#each config.package}{.}{#if more}.{/if}{/each}.web.WebCatalog.*;

/** 確定した Mapper に限定した読出し。更新 SQL は登録しない。 */
@Component
public class WebReads {{
    private final WebCatalog catalog;
    private final Map<String,java.util.function.Function<Map<String,Object>,Object>> calls=new LinkedHashMap<>();
    public WebReads(WebCatalog catalog{#each record.reads}, {name} reader{name}{/each}{#each record.readServices}, {name} service{name}{/each}) {{
        this.catalog=catalog;
{#each record.reads}
{#each methods}
{#if select}
        calls.put({call:java-string}, params -> {{
{#if parameters}            var input=new {mapper}.{inputClass}();
{#each parameters}            input.set{accessor}(({type})params.get({name:java-string}));
{/each}{/if}            return reader{mapper}.{name}({#if parameters}input{/if});
        }});
{/if}
{#if count}
        calls.put({call:java-string}, params -> {{
{#if parameters}            var input=new {mapper}.{inputClass}();
{#each parameters}            input.set{accessor}(({type})params.get({name:java-string}));
{/each}{/if}            return reader{mapper}.{name}({#if parameters}input{/if});
        }});
{/if}
{/each}
{/each}
{#each record.readServices}{#each methods}
        calls.put({call:java-string},params -> service{name}.{method}({#if detail}(String)params.get("id"){/if}{#if dashboard}params{/if}));
{/each}{/each}    }}

    public Object read(String call,Map<String,Object> params) {{
        var reader=calls.get(call);
        if(reader==null)throw new IllegalArgumentException("未対応の読出し: "+call);
        return normalize(reader.apply(params),catalog.methods.get(call));
    }}
    private Object normalize(Object value,Map<String,Object> method) {{
        if(value==null||value instanceof Number||value instanceof String||value instanceof Boolean||value instanceof java.time.temporal.TemporalAccessor)return value;
        if(value instanceof List<?> items)return items.stream().map(v->normalize(v,method)).toList();
        if(value instanceof Map<?,?> results){{var out=new LinkedHashMap<String,Object>();for(var e:results.entrySet())out.put(e.getKey().toString(),normalize(e.getValue(),catalog.methods.get(e.getKey())));return out;}}
        var bean=new BeanWrapperImpl(value);
        var out=new LinkedHashMap<String,Object>();
        // 物理名を Java の名前へ、論理項目を物理列へ結び付けた確定情報で戻す。
        Map<String,Object> row=catalog.rows.values().stream().filter(r->value.getClass().getSimpleName().equals(s(r,"className"))||value.getClass().getSuperclass().getSimpleName().equals(s(r,"className"))).findFirst().orElse(Map.of());
        var parents=new LinkedHashMap<String,Object>();var hidden=new ArrayList<String>();
        for(var column:maps(row.get("columns"))) {{
            Object v=bean.getPropertyValue(s(column,"name"));
            for(var mapping:maps(column.get("mapping"))) {{
                String logical=s(mapping,"item");if(!logical.isEmpty())out.put(logical,v);
                if(!s(mapping,"role").isEmpty())hidden.add(logical);
                if(s(mapping,"role").equals("parent"))parents.put(s(mapping,"referencesTable"),v);
            }}
        }}
        if(!parents.isEmpty())out.put("_parents",parents);
        if(!hidden.isEmpty())out.put("_hidden",hidden);
        // アクセス設計にある対応を優先する。Java 名や SQL 名から表示名を推測しない。
        for(var column:maps(map(method.get("raw")).get("columnBindings"))) for(var c:maps(row.get("columns")))
            if(s(c,"source").equals(s(column,"columnId"))) out.put(s(column,"item"),bean.getPropertyValue(s(c,"name")));
        var projections=new ArrayList<Object>();
        for(var p:maps(method.get("properties"))) {{
            Object v=bean.getPropertyValue(s(p,"name"));
            Object converted=normalize(v,Map.of());out.put(s(p,"name"),converted);
            if(s(p,"name").startsWith("column"))projections.add(converted);
        }}
        if(!projections.isEmpty())out.put("_columns",projections);
        return out;
    }}
}}
