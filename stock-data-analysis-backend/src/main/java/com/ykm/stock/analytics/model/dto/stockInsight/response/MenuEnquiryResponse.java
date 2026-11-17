package com.ykm.stock.analytics.model.dto.stockInsight.response;

import com.ykm.common.common_lib.model.ApiResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@Schema(
        allOf = { ApiResponse.class }   // 告訴 springdoc 要用 allOf
)
public class MenuEnquiryResponse extends ApiResponse {

    private List<MenuTree> menuTrees;

    @Data
    public static class MenuTree{
        private String menuId;
        private String parentId;
        private String name;
        private String icon;
        private String path;
        private List<MenuTree> children = new ArrayList<>();
    }
}
