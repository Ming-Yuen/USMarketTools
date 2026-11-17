package com.ykm.stock.analytics.model.entity;


import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.Comment;

@Data
@Entity
@Comment("前端導覽選單定義，透過 parentId 組成階層式選單樹。")
// 用途：表示「選單」資料的持久化實體，保存相關業務狀態與可追溯欄位。
public class Menu {

    @Id
    @Column(name = "menu_id", nullable = false, updatable = false)
    @Comment("選單唯一識別碼。")
    // 用途：儲存「選單Id」欄位值，供此實體的持久化、查詢或業務判斷使用。
    private Integer menuId;

    @Column(name = "parent_id")
    @Comment("父選單 ID；空值代表最上層選單。")
    // 用途：儲存「parentId」欄位值，供此實體的持久化、查詢或業務判斷使用。
    private Integer parentId;

    @Column(name = "name", nullable = false, length = 32)
    @Comment("選單顯示名稱。")
    // 用途：業務名稱，用於識別及展示此實體。
    private String name;

    @Column(name = "icon", nullable = false, length = 16)
    @Comment("前端選單圖示識別字串。")
    // 用途：儲存「icon」欄位值，供此實體的持久化、查詢或業務判斷使用。
    private String icon;

    @Column(name = "path", nullable = false, length = 255)
    @Comment("前端路由路徑。")
    // 用途：儲存「path」欄位值，供此實體的持久化、查詢或業務判斷使用。
    private String path;
}