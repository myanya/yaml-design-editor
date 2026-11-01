import { Box, Button, MenuItem, Paper, Stack, TextField, Typography } from '@mui/material';
import type { ActionField, JsonValue } from '../api';

type Values = Record<string, JsonValue>;
const object = (value: JsonValue | undefined): Values => value !== null && typeof value === 'object' && !Array.isArray(value) ? value : {};
function update(values: Values, key: string, value: JsonValue | undefined): Values {
  const next = { ...values };
  if (value === undefined) delete next[key]; else next[key] = value;
  return next;
}

/** 定義の表示情報から入力欄を作る。前提・型制約・初期値の評価はサーバに委ねる。 */
export function ActionFields({ fields, values, change, disabled }: {
  fields: ActionField[]; values: Values; change: (value: Values) => void; disabled: boolean;
}) {
  return <Stack spacing={2}>{fields.map((field) => <Input key={field.key} field={field} value={values[field.key!]}
    label={field.label} change={(value) => change(update(values, field.key!, value))} disabled={disabled} />)}</Stack>;
}

function Input({ field, value, label, change, disabled }: {
  field: ActionField; value: JsonValue | undefined; label: string; change: (value: JsonValue | undefined) => void; disabled: boolean;
}) {
  if (field.type === 'object' && !field.required && value === undefined) return <Box>
    <Button disabled={disabled} onClick={() => change(field.default ?? {})}>{label}を入力</Button>
    {field.description && <Typography variant="caption">{field.description}</Typography>}
  </Box>;
  if (field.type === 'object') return <Paper variant="outlined" sx={{ p: 2 }}>
    <Typography sx={{ mb: 2 }}>{label}{field.required && ' *'}</Typography>
    <ActionFields fields={field.fields} values={object(value)} change={change} disabled={disabled} />
    {!field.required && value !== undefined && <Button disabled={disabled} onClick={() => change(undefined)}>この項目を省略</Button>}
  </Paper>;
  if (field.type === 'array') {
    const items = Array.isArray(value) ? value : [];
    return <Paper variant="outlined" sx={{ p: 2 }}>
      <Typography>{label}{field.required && ' *'}</Typography>
      {field.description && <Typography variant="caption">{field.description}</Typography>}
      <Stack spacing={2} sx={{ my: 2 }}>{items.map((item, index) => <Box key={index}>
        <Input field={field.item!} value={item} label={`${label} ${index + 1}`} disabled={disabled}
          change={(next) => change(items.map((old, at) => at === index ? next ?? null : old))} />
        <Button disabled={disabled} onClick={() => change(items.filter((_, at) => at !== index))}>{index + 1} 件目を除く</Button>
      </Box>)}</Stack>
      <Button disabled={disabled || items.length >= 200} onClick={() => change([...items, field.item?.default ?? (field.item?.type === 'object' ? {} : '')])}>{label}を追加</Button>
    </Paper>;
  }
  const boolean = field.type === 'boolean';
  const selection = boolean || field.type === 'enum';
  const numeric = field.type === 'int' || field.type === 'number';
  return <TextField fullWidth size="small" label={label} required={field.required} disabled={disabled} select={selection}
    value={value == null ? '' : String(value)} multiline={field.multiline && !selection} minRows={field.multiline ? 3 : undefined}
    type={numeric ? 'number' : field.type === 'date' ? 'date' : field.type === 'time' ? 'time' : field.type === 'datetime' ? 'datetime-local' : 'text'}
    placeholder={field.placeholder ?? undefined} helperText={field.description ?? (field.type === 'ref' ? '参照先の ID を入力してください' : undefined)}
    slotProps={{ inputLabel: { shrink: true }, htmlInput: { step: field.type === 'int' ? 1 : 'any', maxLength: 10000 }, select: { displayEmpty: true } }}
    onChange={(event) => {
      const text = event.target.value;
      change(boolean ? (text === '' ? undefined : text === 'true') : numeric ? (text === '' ? undefined : Number(text)) : text);
    }}>
    {selection && [<MenuItem key="" value="">未指定</MenuItem>, ...(boolean ? [
      <MenuItem key="true" value="true">はい</MenuItem>, <MenuItem key="false" value="false">いいえ</MenuItem>,
    ] : field.options.map((option) => <MenuItem key={option.value} value={option.value}>{option.label}</MenuItem>))]}
  </TextField>;
}
