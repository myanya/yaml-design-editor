package example.todos.web;

import java.text.MessageFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.support.AbstractMessageSource;
import org.springframework.stereotype.Component;

/** 入力の誤りの確定メッセージ。業務の断りは後続の画面遷移で接続する。 */
@Component("messageSource")
public class WebValidationMessages extends AbstractMessageSource {
    private final Map<String, String> messages = new LinkedHashMap<>();

    public WebValidationMessages() {
        messages.put("validation.NotNull.addCategoryForm.name", "カテゴリ名を入力してください");
        messages.put("validation.Size.addCategoryForm.name", "カテゴリ名は100文字以内で入力してください");
        messages.put("validation.Pattern.addCategoryForm.name", "カテゴリ名の書き方が正しくありません");
        messages.put("validation.NotNull.registerTodoForm.title", "タイトルを入力してください");
        messages.put("validation.Size.registerTodoForm.title", "タイトルは100文字以内で入力してください");
        messages.put("validation.Pattern.registerTodoForm.title", "タイトルの書き方が正しくありません");
        messages.put("validation.NotNull.registerTodoForm.summary", "概要を入力してください");
        messages.put("validation.Size.registerTodoForm.summary", "概要は100文字以内で入力してください");
        messages.put("validation.Pattern.registerTodoForm.summary", "概要の書き方が正しくありません");
        messages.put("validation.UniqueElements.registerTodoForm.tags", "タグに同じものを 2 回選べません");
        messages.put("validation.typeMismatch.registerTodoForm.due", "期限は年月日で入力してください");
        messages.put("validation.NotNull.addTodoCommentForm.date", "記録日を入力してください");
        messages.put("validation.typeMismatch.addTodoCommentForm.date", "記録日は年月日で入力してください");
        messages.put("validation.NotNull.addTodoCommentForm.body", "コメント本文を入力してください");
        messages.put("validation.Size.addTodoCommentForm.body", "コメント本文は1文字以上で入力してください");
        messages.put("validation.NotNull.addTodoTaskForm.title", "作業を入力してください");
        messages.put("validation.Size.addTodoTaskForm.title", "作業は100文字以内で入力してください");
        messages.put("validation.Pattern.addTodoTaskForm.title", "作業の書き方が正しくありません");
        messages.put("validation.Size.editTodoForm.title", "タイトルは100文字以内で入力してください");
        messages.put("validation.Pattern.editTodoForm.title", "タイトルの書き方が正しくありません");
        messages.put("validation.Size.editTodoForm.summary", "概要は100文字以内で入力してください");
        messages.put("validation.Pattern.editTodoForm.summary", "概要の書き方が正しくありません");
        messages.put("validation.UniqueElements.editTodoForm.tags", "タグに同じものを 2 回選べません");
        messages.put("validation.typeMismatch.editTodoForm.createdOn", "作成日は年月日で入力してください");
        messages.put("validation.typeMismatch.editTodoForm.dueOn", "期限は年月日で入力してください");
        messages.put("validation.NotNull.editTodoForm.comments.date", "日付を入力してください");
        messages.put("validation.typeMismatch.editTodoForm.comments.date", "日付は年月日で入力してください");
        messages.put("validation.NotNull.editTodoForm.comments.body", "コメント本文を入力してください");
        messages.put("validation.NotNull.suspendTodoForm.date", "中断日を入力してください");
        messages.put("validation.typeMismatch.suspendTodoForm.date", "中断日は年月日で入力してください");
        messages.put("validation.NotNull.suspendTodoForm.reason", "中断理由を入力してください");
        messages.put("validation.Size.suspendTodoForm.reason", "中断理由は1文字以上で入力してください");
        messages.put("validation.typeMismatch.suspendTodoForm.resumeOn", "再開予定日は年月日で入力してください");
        messages.put("validation.NotNull.resumeTodoForm.date", "再開日を入力してください");
        messages.put("validation.typeMismatch.resumeTodoForm.date", "再開日は年月日で入力してください");
        messages.put("validation.NotNull.completeTodoForm.date", "完了日を入力してください");
        messages.put("validation.typeMismatch.completeTodoForm.date", "完了日は年月日で入力してください");
        messages.put("validation.NotNull.completeTodoForm.result", "完了結果を入力してください");
        messages.put("validation.Size.completeTodoForm.result", "完了結果は1文字以上で入力してください");
        messages.put("validation.NotNull.cancelTodoForm.reason", "中止理由を入力してください");
        messages.put("validation.Size.cancelTodoForm.reason", "中止理由は1文字以上で入力してください");
        messages.put("validation.NotNull.addTagForm.name", "タグ名を入力してください");
        messages.put("validation.Size.addTagForm.name", "タグ名は100文字以内で入力してください");
        messages.put("validation.Pattern.addTagForm.name", "タグ名の書き方が正しくありません");
    }

    @Override
    protected MessageFormat resolveCode(String code, Locale locale) {
        String text = messages.get(code);
        return text == null ? null : new MessageFormat(text, locale);
    }
}
