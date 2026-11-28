package example.todos.data.mapper;

import example.todos.data.model.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 確定したテーブルアクセス設計から生成。 */
@Mapper
public interface TodoMapper {
    FindDetailResult findDetail(@Param("input") FindDetailInput input);





    class FindDetailInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
    }
    class FindDetailResult extends TodoRow {
        private java.util.List<java.lang.String> tags;

        public java.util.List<java.lang.String> getTags() {
            return tags;
        }
        public void setTags(java.util.List<java.lang.String> value) { this.tags = value; }
        private java.util.List<TodoCommentsRow> comments;

        public java.util.List<TodoCommentsRow> getComments() {
            return comments;
        }
        public void setComments(java.util.List<TodoCommentsRow> value) { this.comments = value; }
        private java.util.List<TodoHistoryRow> history;

        public java.util.List<TodoHistoryRow> getHistory() {
            return history;
        }
        public void setHistory(java.util.List<TodoHistoryRow> value) { this.history = value; }
        private java.util.List<TodoTaskRow> tasks;

        public java.util.List<TodoTaskRow> getTasks() {
            return tasks;
        }
        public void setTasks(java.util.List<TodoTaskRow> value) { this.tasks = value; }
        private java.lang.String categoryDisplay;

        public java.lang.String getCategoryDisplay() {
            return categoryDisplay;
        }
        public void setCategoryDisplay(java.lang.String value) { this.categoryDisplay = value; }
        private java.util.List<java.lang.String> tagsDisplay;

        public java.util.List<java.lang.String> getTagsDisplay() {
            return tagsDisplay;
        }
        public void setTagsDisplay(java.util.List<java.lang.String> value) { this.tagsDisplay = value; }
        private java.lang.String followsDisplay;

        public java.lang.String getFollowsDisplay() {
            return followsDisplay;
        }
        public void setFollowsDisplay(java.lang.String value) { this.followsDisplay = value; }
    }
    java.util.List<SelectAllResult> selectAll();





    class SelectAllResult extends TodoRow {
        private java.lang.String column0;

        public java.lang.String getColumn0() {
            return column0;
        }
        public void setColumn0(java.lang.String value) { this.column0 = value; }
        private java.lang.String column1;

        public java.lang.String getColumn1() {
            return column1;
        }
        public void setColumn1(java.lang.String value) { this.column1 = value; }
    }
    java.util.List<SelectTodoDashboardOverdueResult> selectTodoDashboardOverdue(@Param("input") SelectTodoDashboardOverdueInput input);





    class SelectTodoDashboardOverdueInput {
        private java.util.List<java.lang.String> tags;
        public java.util.List<java.lang.String> getTags() { return tags; }
        public void setTags(java.util.List<java.lang.String> value) { this.tags = value; }
        private java.lang.String category;
        public java.lang.String getCategory() { return category; }
        public void setCategory(java.lang.String value) { this.category = value; }
        private java.time.LocalDate today;
        public java.time.LocalDate getToday() { return today; }
        public void setToday(java.time.LocalDate value) { this.today = value; }
    }
    class SelectTodoDashboardOverdueResult extends TodoRow {
        private java.lang.String column0;

        public java.lang.String getColumn0() {
            return column0;
        }
        public void setColumn0(java.lang.String value) { this.column0 = value; }
        private java.lang.String column1;

        public java.lang.String getColumn1() {
            return column1;
        }
        public void setColumn1(java.lang.String value) { this.column1 = value; }
        private java.lang.String column2;

        public java.lang.String getColumn2() {
            return column2;
        }
        public void setColumn2(java.lang.String value) { this.column2 = value; }
        private java.time.LocalDate column3;

        public java.time.LocalDate getColumn3() {
            return column3;
        }
        public void setColumn3(java.time.LocalDate value) { this.column3 = value; }
        private java.time.LocalDate column4;

        public java.time.LocalDate getColumn4() {
            return column4;
        }
        public void setColumn4(java.time.LocalDate value) { this.column4 = value; }
    }

    java.lang.Long countTodoDashboardOverdue(@Param("input") CountTodoDashboardOverdueInput input);




    class CountTodoDashboardOverdueInput {
        private java.util.List<java.lang.String> tags;
        public java.util.List<java.lang.String> getTags() { return tags; }
        public void setTags(java.util.List<java.lang.String> value) { this.tags = value; }
        private java.lang.String category;
        public java.lang.String getCategory() { return category; }
        public void setCategory(java.lang.String value) { this.category = value; }
        private java.time.LocalDate today;
        public java.time.LocalDate getToday() { return today; }
        public void setToday(java.time.LocalDate value) { this.today = value; }
    }
    java.util.List<SelectTodoDashboardAllResult> selectTodoDashboardAll(@Param("input") SelectTodoDashboardAllInput input);





    class SelectTodoDashboardAllInput {
        private java.util.List<java.lang.String> tags;
        public java.util.List<java.lang.String> getTags() { return tags; }
        public void setTags(java.util.List<java.lang.String> value) { this.tags = value; }
        private java.lang.String category;
        public java.lang.String getCategory() { return category; }
        public void setCategory(java.lang.String value) { this.category = value; }
    }
    class SelectTodoDashboardAllResult extends TodoRow {
        private java.lang.String column0;

        public java.lang.String getColumn0() {
            return column0;
        }
        public void setColumn0(java.lang.String value) { this.column0 = value; }
        private java.lang.String column1;

        public java.lang.String getColumn1() {
            return column1;
        }
        public void setColumn1(java.lang.String value) { this.column1 = value; }
        private java.lang.String column2;

        public java.lang.String getColumn2() {
            return column2;
        }
        public void setColumn2(java.lang.String value) { this.column2 = value; }
        private java.lang.String column3;
        private java.lang.Long column3Done;
        public java.lang.Long getColumn3Done() { return column3Done; }
        public void setColumn3Done(java.lang.Long value) { this.column3Done = value; }
        private java.lang.Long column3All;
        public java.lang.Long getColumn3All() { return column3All; }
        public void setColumn3All(java.lang.Long value) { this.column3All = value; }

        public java.lang.String getColumn3() {
            String value = "{done}/{all}";
            value = value.replace("{done}", String.valueOf(column3Done));
            value = value.replace("{all}", String.valueOf(column3All));
            if (value.equals("0/0")) value = "";
            return value;

        }
        public void setColumn3(java.lang.String value) { this.column3 = value; }
        private java.util.List<java.lang.String> column4;

        public java.util.List<java.lang.String> getColumn4() {
            return column4;
        }
        public void setColumn4(java.util.List<java.lang.String> value) { this.column4 = value; }
        private java.time.LocalDate column5;

        public java.time.LocalDate getColumn5() {
            return column5;
        }
        public void setColumn5(java.time.LocalDate value) { this.column5 = value; }
        private java.lang.String column6;

        public java.lang.String getColumn6() {
            return column6;
        }
        public void setColumn6(java.lang.String value) { this.column6 = value; }
        private java.time.LocalDate column7;

        public java.time.LocalDate getColumn7() {
            return column7;
        }
        public void setColumn7(java.time.LocalDate value) { this.column7 = value; }
        private java.time.LocalDate column8;

        public java.time.LocalDate getColumn8() {
            return column8;
        }
        public void setColumn8(java.time.LocalDate value) { this.column8 = value; }
    }

    java.lang.Long countTodoDashboardAll(@Param("input") CountTodoDashboardAllInput input);




    class CountTodoDashboardAllInput {
        private java.util.List<java.lang.String> tags;
        public java.util.List<java.lang.String> getTags() { return tags; }
        public void setTags(java.util.List<java.lang.String> value) { this.tags = value; }
        private java.lang.String category;
        public java.lang.String getCategory() { return category; }
        public void setCategory(java.lang.String value) { this.category = value; }
    }
    FindByIdResult findById(@Param("input") FindByIdInput input);





    class FindByIdInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
    }
    class FindByIdResult extends TodoRow {
    }



    int insert(@Param("input") TodoRow input);




    java.lang.String maxId();






    int insertTodoTags(@Param("input") TodoTagsRow input);





    int insertTodoHistory(@Param("input") TodoHistoryRow input);




    java.lang.Long maxSeqTodoHistory(@Param("input") MaxSeqTodoHistoryInput input);



    class MaxSeqTodoHistoryInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
    }



    int insertTodoComments(@Param("input") TodoCommentsRow input);




    java.lang.Long maxSeqTodoComments(@Param("input") MaxSeqTodoCommentsInput input);



    class MaxSeqTodoCommentsInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
    }




    int addTodoComment(@Param("input") AddTodoCommentInput input);

    class AddTodoCommentInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
        private java.time.LocalDate contextToday;
        public java.time.LocalDate getContextToday() { return contextToday; }
        public void setContextToday(java.time.LocalDate value) { this.contextToday = value; }
        private java.lang.Long nextVersion;
        public java.lang.Long getNextVersion() { return nextVersion; }
        public void setNextVersion(java.lang.Long value) { this.nextVersion = value; }
    }




    int editTodoCommentCommentsRow(@Param("input") EditTodoCommentCommentsRowInput input);

    class EditTodoCommentCommentsRowInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
        private java.lang.Long row;
        public java.lang.Long getRow() { return row; }
        public void setRow(java.lang.Long value) { this.row = value; }
        private java.time.LocalDate date;
        public java.time.LocalDate getDate() { return date; }
        public void setDate(java.time.LocalDate value) { this.date = value; }
        private java.lang.String body;
        public java.lang.String getBody() { return body; }
        public void setBody(java.lang.String value) { this.body = value; }
    }




    int editTodoComment(@Param("input") EditTodoCommentInput input);

    class EditTodoCommentInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
        private java.time.LocalDate contextToday;
        public java.time.LocalDate getContextToday() { return contextToday; }
        public void setContextToday(java.time.LocalDate value) { this.contextToday = value; }
        private java.lang.Long nextVersion;
        public java.lang.Long getNextVersion() { return nextVersion; }
        public void setNextVersion(java.lang.Long value) { this.nextVersion = value; }
    }





    int deleteTodoCommentCommentsRow(@Param("input") DeleteTodoCommentCommentsRowInput input);
    class DeleteTodoCommentCommentsRowInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
        private java.lang.Long row;
        public java.lang.Long getRow() { return row; }
        public void setRow(java.lang.Long value) { this.row = value; }
    }




    int deleteTodoComment(@Param("input") DeleteTodoCommentInput input);

    class DeleteTodoCommentInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
        private java.time.LocalDate contextToday;
        public java.time.LocalDate getContextToday() { return contextToday; }
        public void setContextToday(java.time.LocalDate value) { this.contextToday = value; }
        private java.lang.Long nextVersion;
        public java.lang.Long getNextVersion() { return nextVersion; }
        public void setNextVersion(java.lang.Long value) { this.nextVersion = value; }
    }




    int addTodoTask(@Param("input") AddTodoTaskInput input);

    class AddTodoTaskInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
        private java.time.LocalDate contextToday;
        public java.time.LocalDate getContextToday() { return contextToday; }
        public void setContextToday(java.time.LocalDate value) { this.contextToday = value; }
        private java.lang.Long nextVersion;
        public java.lang.Long getNextVersion() { return nextVersion; }
        public void setNextVersion(java.lang.Long value) { this.nextVersion = value; }
    }




    int deleteTodoTask(@Param("input") DeleteTodoTaskInput input);

    class DeleteTodoTaskInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
        private java.time.LocalDate contextToday;
        public java.time.LocalDate getContextToday() { return contextToday; }
        public void setContextToday(java.time.LocalDate value) { this.contextToday = value; }
        private java.lang.Long nextVersion;
        public java.lang.Long getNextVersion() { return nextVersion; }
        public void setNextVersion(java.lang.Long value) { this.nextVersion = value; }
    }




    int editTodo(@Param("input") EditTodoInput input);

    class EditTodoInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
        private java.lang.String title;
        public java.lang.String getTitle() { return title; }
        public void setTitle(java.lang.String value) { this.title = value; }
        private java.lang.String summary;
        public java.lang.String getSummary() { return summary; }
        public void setSummary(java.lang.String value) { this.summary = value; }
        private java.lang.String category;
        public java.lang.String getCategory() { return category; }
        public void setCategory(java.lang.String value) { this.category = value; }
        private java.time.LocalDate createdOn;
        public java.time.LocalDate getCreatedOn() { return createdOn; }
        public void setCreatedOn(java.time.LocalDate value) { this.createdOn = value; }
        private java.time.LocalDate dueOn;
        public java.time.LocalDate getDueOn() { return dueOn; }
        public void setDueOn(java.time.LocalDate value) { this.dueOn = value; }
        private java.lang.String details;
        public java.lang.String getDetails() { return details; }
        public void setDetails(java.lang.String value) { this.details = value; }
        private java.lang.String result;
        public java.lang.String getResult() { return result; }
        public void setResult(java.lang.String value) { this.result = value; }
        private java.time.LocalDate contextToday;
        public java.time.LocalDate getContextToday() { return contextToday; }
        public void setContextToday(java.time.LocalDate value) { this.contextToday = value; }
        private java.lang.Long nextVersion;
        public java.lang.Long getNextVersion() { return nextVersion; }
        public void setNextVersion(java.lang.Long value) { this.nextVersion = value; }
        private java.lang.Boolean clearCategory;
        public java.lang.Boolean getClearCategory() { return clearCategory; }
        public void setClearCategory(java.lang.Boolean value) { this.clearCategory = value; }
        private java.lang.Boolean clearDueOn;
        public java.lang.Boolean getClearDueOn() { return clearDueOn; }
        public void setClearDueOn(java.lang.Boolean value) { this.clearDueOn = value; }
        private java.lang.Boolean clearDetails;
        public java.lang.Boolean getClearDetails() { return clearDetails; }
        public void setClearDetails(java.lang.Boolean value) { this.clearDetails = value; }
        private java.lang.Boolean clearResult;
        public java.lang.Boolean getClearResult() { return clearResult; }
        public void setClearResult(java.lang.Boolean value) { this.clearResult = value; }
    }





    int deleteTodoTags(@Param("input") DeleteTodoTagsInput input);
    class DeleteTodoTagsInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
    }




    int startTodo(@Param("input") StartTodoInput input);

    class StartTodoInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
        private java.time.LocalDate contextToday;
        public java.time.LocalDate getContextToday() { return contextToday; }
        public void setContextToday(java.time.LocalDate value) { this.contextToday = value; }
        private java.lang.Long nextVersion;
        public java.lang.Long getNextVersion() { return nextVersion; }
        public void setNextVersion(java.lang.Long value) { this.nextVersion = value; }
    }




    int suspendTodo(@Param("input") SuspendTodoInput input);

    class SuspendTodoInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
        private java.time.LocalDate contextToday;
        public java.time.LocalDate getContextToday() { return contextToday; }
        public void setContextToday(java.time.LocalDate value) { this.contextToday = value; }
        private java.lang.Long nextVersion;
        public java.lang.Long getNextVersion() { return nextVersion; }
        public void setNextVersion(java.lang.Long value) { this.nextVersion = value; }
    }




    int resumeTodo(@Param("input") ResumeTodoInput input);

    class ResumeTodoInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
        private java.time.LocalDate contextToday;
        public java.time.LocalDate getContextToday() { return contextToday; }
        public void setContextToday(java.time.LocalDate value) { this.contextToday = value; }
        private java.lang.Long nextVersion;
        public java.lang.Long getNextVersion() { return nextVersion; }
        public void setNextVersion(java.lang.Long value) { this.nextVersion = value; }
    }




    int completeTodo(@Param("input") CompleteTodoInput input);

    class CompleteTodoInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
        private java.time.LocalDate date;
        public java.time.LocalDate getDate() { return date; }
        public void setDate(java.time.LocalDate value) { this.date = value; }
        private java.lang.String result;
        public java.lang.String getResult() { return result; }
        public void setResult(java.lang.String value) { this.result = value; }
        private java.time.LocalDate contextToday;
        public java.time.LocalDate getContextToday() { return contextToday; }
        public void setContextToday(java.time.LocalDate value) { this.contextToday = value; }
        private java.lang.Long nextVersion;
        public java.lang.Long getNextVersion() { return nextVersion; }
        public void setNextVersion(java.lang.Long value) { this.nextVersion = value; }
    }




    int cancelTodo(@Param("input") CancelTodoInput input);

    class CancelTodoInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
        private java.lang.String reason;
        public java.lang.String getReason() { return reason; }
        public void setReason(java.lang.String value) { this.reason = value; }
        private java.time.LocalDate contextToday;
        public java.time.LocalDate getContextToday() { return contextToday; }
        public void setContextToday(java.time.LocalDate value) { this.contextToday = value; }
        private java.lang.Long nextVersion;
        public java.lang.Long getNextVersion() { return nextVersion; }
        public void setNextVersion(java.lang.Long value) { this.nextVersion = value; }
    }




    int todoTaskFinish(@Param("input") TodoTaskFinishInput input);

    class TodoTaskFinishInput {
        private java.lang.String todoId;
        public java.lang.String getTodoId() { return todoId; }
        public void setTodoId(java.lang.String value) { this.todoId = value; }
        private java.time.LocalDate contextToday;
        public java.time.LocalDate getContextToday() { return contextToday; }
        public void setContextToday(java.time.LocalDate value) { this.contextToday = value; }
        private java.lang.Long ownerNextVersion;
        public java.lang.Long getOwnerNextVersion() { return ownerNextVersion; }
        public void setOwnerNextVersion(java.lang.Long value) { this.ownerNextVersion = value; }
    }
}
