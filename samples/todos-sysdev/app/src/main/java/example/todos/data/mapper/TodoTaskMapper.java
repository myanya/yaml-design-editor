package example.todos.data.mapper;

import example.todos.data.model.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 確定したテーブルアクセス設計から生成。 */
@Mapper
public interface TodoTaskMapper {
    FindByIdResult findById(@Param("input") FindByIdInput input);





    class FindByIdInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
    }
    class FindByIdResult extends TodoTaskRow {
    }



    int insert(@Param("input") TodoTaskRow input);




    java.lang.String maxId(@Param("input") MaxIdInput input);



    class MaxIdInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
    }


    java.lang.Long maxSeq(@Param("input") MaxSeqInput input);



    class MaxSeqInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
    }




    int todoTaskFinish(@Param("input") TodoTaskFinishInput input);

    class TodoTaskFinishInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
    }

    java.lang.Long countCompleteTodoNoOpenTask(@Param("input") CountCompleteTodoNoOpenTaskInput input);




    class CountCompleteTodoNoOpenTaskInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
    }

    java.lang.Long countClosedHasNoOpenTask(@Param("input") CountClosedHasNoOpenTaskInput input);




    class CountClosedHasNoOpenTaskInput {
        private java.lang.String todoId;
        public java.lang.String getTodoId() { return todoId; }
        public void setTodoId(java.lang.String value) { this.todoId = value; }
    }
}
