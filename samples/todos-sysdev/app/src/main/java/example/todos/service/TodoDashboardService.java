package example.todos.service;

import java.util.*;
import org.springframework.stereotype.Service;
import example.todos.data.mapper.*;

/** 確定した一覧の読出しをまとめる。更新は行わない。 */
@Service
public class TodoDashboardService {
    private final CategoryMapper categoryMapper;
    private final TagMapper tagMapper;
    private final TodoMapper todoMapper;
    private final TodoTaskMapper todoTaskMapper;
    public TodoDashboardService(CategoryMapper categoryMapper, TagMapper tagMapper, TodoMapper todoMapper, TodoTaskMapper todoTaskMapper) {
        this.categoryMapper=categoryMapper;
        this.tagMapper=tagMapper;
        this.todoMapper=todoMapper;
        this.todoTaskMapper=todoTaskMapper;
    }
    public Map<String,Object> show(Map<String,Object> params) {
        var result=new LinkedHashMap<String,Object>();
        {
            var input=new TodoMapper.SelectTodoDashboardOverdueInput();
            input.setTags((java.util.List<java.lang.String>)params.get("tags"));
            input.setCategory((java.lang.String)params.get("category"));
            input.setToday((java.time.LocalDate)params.get("today"));
            result.put("TodoMapper.selectTodoDashboardOverdue",todoMapper.selectTodoDashboardOverdue(input));
        }
        {
            var input=new TodoMapper.CountTodoDashboardOverdueInput();
            input.setTags((java.util.List<java.lang.String>)params.get("tags"));
            input.setCategory((java.lang.String)params.get("category"));
            input.setToday((java.time.LocalDate)params.get("today"));
            result.put("TodoMapper.countTodoDashboardOverdue",todoMapper.countTodoDashboardOverdue(input));
        }
        {
            var input=new TodoMapper.SelectTodoDashboardAllInput();
            input.setTags((java.util.List<java.lang.String>)params.get("tags"));
            input.setCategory((java.lang.String)params.get("category"));
            result.put("TodoMapper.selectTodoDashboardAll",todoMapper.selectTodoDashboardAll(input));
        }
        {
            var input=new TodoMapper.CountTodoDashboardAllInput();
            input.setTags((java.util.List<java.lang.String>)params.get("tags"));
            input.setCategory((java.lang.String)params.get("category"));
            result.put("TodoMapper.countTodoDashboardAll",todoMapper.countTodoDashboardAll(input));
        }
        {
            result.put("CategoryMapper.selectTodoDashboardCategories",categoryMapper.selectTodoDashboardCategories());
        }
        {
            result.put("CategoryMapper.countTodoDashboardCategories",categoryMapper.countTodoDashboardCategories());
        }
        {
            result.put("TagMapper.selectTodoDashboardTags",tagMapper.selectTodoDashboardTags());
        }
        {
            result.put("TagMapper.countTodoDashboardTags",tagMapper.countTodoDashboardTags());
        }
        {
            result.put("CategoryMapper.selectTodoDashboardCategoriesMaster",categoryMapper.selectTodoDashboardCategoriesMaster());
        }
        {
            result.put("CategoryMapper.countTodoDashboardCategoriesMaster",categoryMapper.countTodoDashboardCategoriesMaster());
        }
        {
            result.put("TagMapper.selectTodoDashboardTagsMaster",tagMapper.selectTodoDashboardTagsMaster());
        }
        {
            result.put("TagMapper.countTodoDashboardTagsMaster",tagMapper.countTodoDashboardTagsMaster());
        }
        {
            result.put("TagMapper.selectAll",tagMapper.selectAll());
        }
        {
            result.put("CategoryMapper.selectAll",categoryMapper.selectAll());
        }
        return result;
    }
}
