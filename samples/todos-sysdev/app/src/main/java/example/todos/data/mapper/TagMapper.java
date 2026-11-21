package example.todos.data.mapper;

import example.todos.data.model.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 確定したテーブルアクセス設計から生成。 */
@Mapper
public interface TagMapper {
    FindDetailResult findDetail(@Param("input") FindDetailInput input);





    class FindDetailInput {
        private java.lang.String id;
        public java.lang.String getId() { return id; }
        public void setId(java.lang.String value) { this.id = value; }
    }
    class FindDetailResult extends TagRow {
    }
    java.util.List<SelectAllResult> selectAll();





    class SelectAllResult extends TagRow {
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
    java.util.List<SelectTodoDashboardTagsResult> selectTodoDashboardTags();





    class SelectTodoDashboardTagsResult extends TagRow {
        private java.lang.Long count0;

        public java.lang.Long getCount0() {
            return count0;
        }
        public void setCount0(java.lang.Long value) { this.count0 = value; }
        private java.lang.Long count1;

        public java.lang.Long getCount1() {
            return count1;
        }
        public void setCount1(java.lang.Long value) { this.count1 = value; }
        private java.lang.Long count2;

        public java.lang.Long getCount2() {
            return count2;
        }
        public void setCount2(java.lang.Long value) { this.count2 = value; }
        private java.lang.Long count3;

        public java.lang.Long getCount3() {
            return count3;
        }
        public void setCount3(java.lang.Long value) { this.count3 = value; }
        private java.lang.Long count4;

        public java.lang.Long getCount4() {
            return count4;
        }
        public void setCount4(java.lang.Long value) { this.count4 = value; }
    }

    java.lang.Long countTodoDashboardTags();




    java.util.List<SelectTodoDashboardTagsMasterResult> selectTodoDashboardTagsMaster();





    class SelectTodoDashboardTagsMasterResult extends TagRow {
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
        private java.lang.Long column2;

        public java.lang.Long getColumn2() {
            return column2;
        }
        public void setColumn2(java.lang.Long value) { this.column2 = value; }
    }

    java.lang.Long countTodoDashboardTagsMaster();







    int insert(@Param("input") TagRow input);




    java.lang.String maxId();



}
