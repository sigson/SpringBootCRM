package app.springbootcrm.navigation;

import app.springbootcrm.auth.AdminCheck;
import app.springbootcrm.interfaces.InterfaceLayout;
import app.springbootcrm.interfaces.InterfaceLayoutRepository;
import app.springbootcrm.interfaces.LayoutNode;
import app.springbootcrm.metadata.TypeRegistry;
import app.springbootcrm.metadata.TypeRegistry.TypeDescriptor;
import app.springbootcrm.user.User;
import app.springbootcrm.user.UserRepository;
import domain.core.access.AccessContext;
import domain.core.access.AccessContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Сборка дерева навигации для <b>текущего</b> пользователя.
 *
 * <h3>Алгоритм:</h3>
 * <ol>
 *   <li><b>Выбор layout'а.</b> Если пользователю назначен активный
 *       {@link InterfaceLayout} (реквизит {@code User.interfaceLayout}) с непустым
 *       деревом — берём его. Иначе — сгенерированный {@link DefaultLayoutFactory}
 *       дефолт (всегда актуальный относительно метаданных).</li>
 *   <li><b>Фильтрация по правам (на сервере).</b> {@code OBJECT}-узел остаётся, только
 *       если у пользователя есть READ-доступ к {@code typeId} ({@code resolveRepository.canRead()}
 *       — тот же резолвер, что enforce'ит REST). {@code TOOL} — по флагу
 *       {@code adminOnly}. {@code GROUP} убирается, если после фильтрации в ней не
 *       осталось ни одного доступного листа. Пользователь не видит объекты, на
 *       которые не имеет прав.</li>
 * </ol>
 *
 * <p><b>Админ</b> проходит фильтр полностью (bypass-флаги в {@code resolveRepository}),
 * поэтому видит всё, что описано в layout'е.
 */
@Service
public class NavigationService {

    private final DefaultLayoutFactory defaults;
    private final ToolRegistry tools;
    private final TypeRegistry types;
    private final InterfaceLayoutRepository interfaceLayouts;
    private final UserRepository users;
    private final AdminCheck admin;
    private final AccessContextHolder ctxHolder;

    public NavigationService(DefaultLayoutFactory defaults,
                             ToolRegistry tools,
                             TypeRegistry types,
                             InterfaceLayoutRepository interfaceLayouts,
                             UserRepository users,
                             AdminCheck admin,
                             AccessContextHolder ctxHolder) {
        this.defaults = defaults;
        this.tools = tools;
        this.types = types;
        this.interfaceLayouts = interfaceLayouts;
        this.users = users;
        this.admin = admin;
        this.ctxHolder = ctxHolder;
    }

    @Transactional(readOnly = true)
    public List<NavNode> resolveForCurrentUser() {
        List<LayoutNode> layout = pickLayout();
        AccessContext ctx = ctxHolder.tryGet().orElse(null);
        boolean isAdmin = admin.isAdmin();

        AtomicInteger groupSeq = new AtomicInteger();
        List<NavNode> out = new ArrayList<>();
        for (LayoutNode node : layout) {
            resolveNode(node, ctx, isAdmin, groupSeq).ifPresent(out::add);
        }
        return out;
    }

    // ------------------------------------------------------------------ layout

    private List<LayoutNode> pickLayout() {
        UUID uid = admin.currentUserIdOrNull();
        if (uid != null) {
            User user = users.findById(uid).orElse(null);
            if (user != null) {
                UUID layoutId = user.getInterfaceLayoutId();   // реквизит interfaceLayout пользователя
                if (layoutId != null) {
                    InterfaceLayout il = interfaceLayouts.findById(layoutId).orElse(null);
                    if (il != null && il.isEnabled() && !il.getLayout().isEmpty()) {
                        return il.getLayout();
                    }
                }
            }
        }
        return defaults.build();
    }

    // ---------------------------------------------------------------- pruning

    private Optional<NavNode> resolveNode(LayoutNode node, AccessContext ctx,
                                          boolean isAdmin, AtomicInteger groupSeq) {
        if (node == null || node.kind() == null) return Optional.empty();
        return switch (node.kind()) {
            case LayoutNode.KIND_OBJECT -> resolveObject(node, ctx, isAdmin);
            case LayoutNode.KIND_TOOL   -> resolveTool(node, isAdmin);
            case LayoutNode.KIND_GROUP  -> resolveGroup(node, ctx, isAdmin, groupSeq);
            default -> Optional.empty();
        };
    }

    private Optional<NavNode> resolveObject(LayoutNode node, AccessContext ctx, boolean isAdmin) {
        if (node.typeId() == null) return Optional.empty();
        long typeId = node.typeId();
        TypeDescriptor td = types.byTypeId(typeId);
        if (td == null) return Optional.empty();          // тип исчез из метаданных — прячем
        if (td.isTabularPart()) return Optional.empty();   // ТЧ не является самостоятельным пунктом
        // Свободные контроллеры (NONSTANDARD) не рендерятся generic-объектом: их
        // место — TOOL-узел (route /tool/{key}), а представление даёт фронтовый хук.
        if (app.springbootcrm.metadata.TypeRegistry.REPRESENTATION_NONSTANDARD.equals(td.representation())) {
            return Optional.empty();
        }
        if (!canRead(typeId, ctx, isAdmin)) return Optional.empty();

        String label = (node.title() != null && !node.title().isBlank())
                ? node.title() : td.pluralLabel();
        String icon = (node.icon() != null && !node.icon().isBlank())
                ? node.icon() : td.iconHint();
        return Optional.of(new NavNode(
                td.slug(), LayoutNode.KIND_OBJECT, label, icon,
                "/o/" + td.slug(), typeId, td.slug(), null, List.of()));
    }

    private Optional<NavNode> resolveTool(LayoutNode node, boolean isAdmin) {
        ToolRegistry.ToolNode t = tools.byKey(node.tool());
        if (t == null) return Optional.empty();
        if (t.adminOnly() && !isAdmin) return Optional.empty();
        String label = (node.title() != null && !node.title().isBlank()) ? node.title() : t.label();
        String icon = (node.icon() != null && !node.icon().isBlank()) ? node.icon() : t.icon();
        return Optional.of(new NavNode(
                t.key(), LayoutNode.KIND_TOOL, label, icon,
                t.route(), t.typeId(), null, t.key(), List.of()));
    }

    private Optional<NavNode> resolveGroup(LayoutNode node, AccessContext ctx,
                                           boolean isAdmin, AtomicInteger groupSeq) {
        List<NavNode> children = new ArrayList<>();
        for (LayoutNode child : node.children()) {
            resolveNode(child, ctx, isAdmin, groupSeq).ifPresent(children::add);
        }
        if (children.isEmpty()) return Optional.empty();   // пустая ветка — прячем
        String id = "grp:" + groupSeq.incrementAndGet();
        return Optional.of(new NavNode(
                id, LayoutNode.KIND_GROUP,
                node.title() == null ? "" : node.title(),
                node.icon(), null, null, null, null, children));
    }

    /** READ-доступ к типу (зеркалит REST-enforcement); admin — всегда true. */
    private boolean canRead(long typeId, AccessContext ctx, boolean isAdmin) {
        if (isAdmin) return true;
        if (ctx == null) return false;
        try {
            return ctx.resolveRepository(typeId).canRead();
        } catch (RuntimeException e) {
            return false;   // тип не в снапшоте / нерезолвим — безопасно прячем
        }
    }
}
