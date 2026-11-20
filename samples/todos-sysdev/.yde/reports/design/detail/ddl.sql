-- 生成物（レポート ddl）: テーブル設計書（詳細設計項目一覧・詳細設計項目台帳・型対応表・補正一覧（詳細設計））と基本設計項目一覧・基本設計項目台帳から作る。手で直さない
{#each tables}

-- {label}
CREATE TABLE {name} (
{#each columns}
  {name} {type}{#if default} DEFAULT {#if default.date}DATE {/if}{#if default.quoted}{default.value:sql-string}{/if}{#if default.plain}{default.value}{/if}{/if}{#if notNull} NOT NULL{/if}{#if check} CHECK ({name} IN ({#each check}{v:sql-string}{#if more}, {/if}{/each})){/if},
{/each}
  CONSTRAINT {primaryKeyName} PRIMARY KEY ({#each primaryKey}{v}{#if more}, {/if}{/each})
);
{#each indexes}
CREATE {#if unique}UNIQUE {/if}INDEX {name} ON {table} ({#each columns}{v}{#if more}, {/if}{/each});
{/each}
{/each}

-- 外部キー（すべてのテーブルを作った後に足す）
{#each tables}
{#each foreignKeys}
ALTER TABLE {table} ADD CONSTRAINT {name} FOREIGN KEY ({#each columns}{v}{#if more}, {/if}{/each}) REFERENCES {references} ({#each referencedColumns}{v}{#if more}, {/if}{/each}){#if onDelete} ON DELETE {onDelete}{/if};
{/each}
{/each}
