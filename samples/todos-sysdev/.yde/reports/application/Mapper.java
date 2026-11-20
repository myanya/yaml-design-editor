package {#each config.package}{.}{#if more}.{/if}{/each}.data.mapper;

import {#each config.package}{.}{#if more}.{/if}{/each}.data.model.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 確定したテーブルアクセス設計から生成。 */
@Mapper
public interface {mapper.name} {{
{#each mapper.methods}
{#if select}    {#if many}java.util.List<{resultClass}>{/if}{#if single}{resultClass}{/if} {name}({#if parameters}@Param("input") {inputClass} input{/if});{/if}
{#if count}    java.lang.Long {name}({#if parameters}@Param("input") {inputClass} input{/if});{/if}
{#if max}    {maxColumn.model.java.qualifiedName} {name}({#if parameters}@Param("input") {inputClass} input{/if});{/if}
{#if insert}    int {name}(@Param("input") {row.className} input);{/if}
{#if update}    int {name}({#if parameters}@Param("input") {inputClass} input{/if});{/if}
{#if delete}    int {name}({#if parameters}@Param("input") {inputClass} input{/if});{/if}
{#if parameters}
    class {inputClass} {{
{#each parameters}        private {type} {name};
        public {type} get{accessor}() {{ return {name}; }}
        public void set{accessor}({type} value) {{ this.{name} = value; }}
{/each}    }}
{/if}
{#if select}
    class {resultClass} extends {row.className} {{
{#each properties}        private {type} {name};
{#if parts}{#each parts}        private {type} {name};
        public {type} get{accessor}() {{ return {name}; }}
        public void set{accessor}({type} value) {{ this.{name} = value; }}
{/each}{/if}
        public {type} get{accessor}() {{
{#if format}            String value = {format:java-string};
{#if parts}{#each parts}            value = value.replace({hole:java-string}, String.valueOf({name}));
{/each}{/if}{#if replacements}{#each replacements}            if (value.equals({from:java-string})) value = {to:java-string};
{/each}{/if}            return value;
{/if}{#if column}            return {name};{/if}{#if lookup}            return {name};{/if}{#if count}            return {name};{/if}{#if collection}            return {name};{/if}
        }}
        public void set{accessor}({type} value) {{ this.{name} = value; }}
{/each}    }}
{/if}
{/each}}}
