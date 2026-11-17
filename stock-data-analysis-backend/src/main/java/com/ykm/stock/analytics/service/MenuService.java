package com.ykm.stock.analytics.service;

import com.ykm.stock.analytics.converter.jpa.MenuConverter;
import com.ykm.stock.analytics.repository.jpa.MenuRepository;
import com.ykm.stock.analytics.model.entity.Menu;
import com.ykm.stock.analytics.model.dto.stockInsight.response.MenuEnquiryResponse;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@AllArgsConstructor
@Service
// 用途：負責「選單服務」相關的業務協調與處理，集中管理此領域的服務邏輯。
public class MenuService {
    private MenuRepository menuRepository;
    private MenuConverter menuConverter;
    // 用途：處理enquiry，作為「選單服務」流程中的處理步驟。
    public MenuEnquiryResponse enquiry() {
        List<Menu> menuList = menuRepository.findAll();
        return menuConverter.mapMenuListToResponse(menuList);
    }
}
