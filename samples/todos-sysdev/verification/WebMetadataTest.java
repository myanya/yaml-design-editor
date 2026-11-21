package example.todos.web;

import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static example.todos.web.WebCatalog.*;
import static org.junit.jupiter.api.Assertions.*;

/** 確定設計の名前や URL を変えても、画面処理に元の業務名を残さない。 */
class WebMetadataTest {
    @Test void filtersMatrixAndHomeUseSavedNames() throws Exception {
        var catalog=new WebCatalog();
        String home=catalog.homeScreen();catalog.destinations.put(home,"/work");
        var screen=map(catalog.pages.get(home).get("screen"));
        rename(screen);
        for(var method:catalog.methods.values())for(var parameter:maps(method.get("parameters")))
            if(s(parameter,"name").equals("category"))parameter.put("name","group");
        var request=new MockHttpServletRequest();request.setContextPath("/app");
        request.setParameter("by-group.cell","CAT-123,OPEN");request.setParameter("review-day","2026-11-20");
        var page=new WebPage(catalog,null,request,LocalDate.of(2026,11,21));page.setScreen(home);
        assertEquals("CAT-123",page.parameters().get("group"));
        assertFalse(page.parameters().containsKey("category"));
        assertEquals(LocalDate.of(2026,11,20),page.parameters().get("today"));
        assertEquals("/app/work",page.homeUrl());
        var matrix=findMatrix(screen);
        String url=page.matrixUrl(matrix,Map.of("id","CAT-123"),Map.of("axisValue","OPEN"));
        assertTrue(url.startsWith("/app/work?by-group.cell=CAT-123%2COPEN"),url);
        assertTrue(url.endsWith("review-day=2026-11-20"),url);
    }
    private void rename(Object value) {
        if(value instanceof Map<?,?>){var m=map(value);
            if(s(m,"item").equals("category"))m.put("item","group");
            if(s(m,"item").equals("date"))m.put("item","review-day");
            if(s(m,"id").equals("categories"))m.put("id","by-group");
            new ArrayList<>(m.values()).forEach(this::rename);
        } else if(value instanceof List<?>)list(value).forEach(this::rename);
    }
    private Map<String,Object> findMatrix(Object value) {
        if(value instanceof Map<?,?>){var m=map(value);if(s(map(m.get("rows")),"entity").equals("category")&&m.containsKey("cell"))return m;
            for(var v:m.values()){var found=findMatrix(v);if(!found.isEmpty())return found;}
        }else if(value instanceof List<?>)for(var v:list(value)){var found=findMatrix(v);if(!found.isEmpty())return found;}
        return Map.of();
    }
}
