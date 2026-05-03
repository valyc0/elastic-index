package com.example.ragclient.views;

import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.applayout.DrawerToggle;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.theme.lumo.LumoUtility;

public class MainLayout extends AppLayout {

    public MainLayout() {
        createHeader();
        createDrawer();
    }

    private void createHeader() {
        H1 appName = new H1("RAG Client");
        appName.addClassNames(
            LumoUtility.FontSize.LARGE,
            LumoUtility.Margin.NONE
        );

        Span version = new Span("v1.0");
        version.getStyle()
            .set("font-size", "var(--lumo-font-size-s)")
            .set("color", "var(--lumo-secondary-text-color)")
            .set("margin-left", "var(--lumo-space-m)");

        HorizontalLayout header = new HorizontalLayout(
            new DrawerToggle(),
            appName,
            version
        );
        header.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
        header.setWidthFull();
        header.addClassNames(
            LumoUtility.Padding.Vertical.NONE,
            LumoUtility.Padding.Horizontal.MEDIUM
        );

        addToNavbar(header);
    }

    private void createDrawer() {
        SideNav nav = new SideNav();
        
        SideNavItem queryItem = new SideNavItem("Chat RAG", QueryView.class);
        queryItem.setPrefixComponent(new Span("💬"));
        
        SideNavItem uploadItem = new SideNavItem("Upload Document", UploadView.class);
        uploadItem.setPrefixComponent(new Span("📤"));
        
        SideNavItem documentsItem = new SideNavItem("Documents", DocumentListView.class);
        documentsItem.setPrefixComponent(new Span("📚"));
        
        SideNavItem statusItem = new SideNavItem("System Status", StatusView.class);
        statusItem.setPrefixComponent(new Span("💚"));
        
        nav.addItem(queryItem);
        nav.addItem(uploadItem);
        nav.addItem(documentsItem);
        nav.addItem(statusItem);

        VerticalLayout drawerLayout = new VerticalLayout(nav);
        drawerLayout.setSizeFull();
        drawerLayout.setPadding(false);
        drawerLayout.setSpacing(false);
        
        addToDrawer(drawerLayout);
    }
}
