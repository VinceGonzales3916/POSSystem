import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicArrowButton;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.table.*;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class CofteaPOSSystem {

    // 1. MAIN METHOD
    public static void main(String[] args) {

        SwingUtilities.invokeLater(() -> {

            CofteaPOSSystem pos = new CofteaPOSSystem();

            pos.initializeProducts();
            pos.initializeAddOns();
            pos.createDashboardUI();

        });
    }
    // 2. COLORS AND SHARED VARIABLES
    private final Color black = new Color(25, 25, 25);
    private final Color white = Color.WHITE;
    private final Color blue = new Color(53, 179, 242);
    private final Color red = new Color(255, 41, 41);
    private final Color green = new Color(0, 191, 105);
    private final Color gray = new Color(217, 217, 217);
    private final Color pillGray = new Color(203, 211, 222);

    private JFrame dashboardFrame;
    private JFrame menuFrame;

    private JTable orderTable;
    private DefaultTableModel orderTableModel;

    private JLabel subtotalLabel;
    private JLabel addedItemsLabel;
    private JLabel categoryLabel;

    private JPanel productPanel;

    // ---- TAX AND DISCOUNT SETTINGS ----

    private static final BigDecimal VAT_RATE =
        new BigDecimal("0.12");

    private static final BigDecimal VAT_DIVISOR =
        new BigDecimal("1.12");

    private static final BigDecimal SENIOR_PWD_DISCOUNT_RATE =
        new BigDecimal("0.20");

    private static final boolean PRICES_INCLUDE_VAT = false;

    private final List<OrderLine> orderLines =
        new ArrayList<>();

    private final List<TransactionRecord> transactions =
        new ArrayList<>();

    private TransactionRecord lastTransaction;

    private int transactionCounter = 0;

    private final Map<String, PendingItem> pendingOrder =
        new LinkedHashMap<>();

    private final Map<String, String[]> products =
        new LinkedHashMap<>();

    private final Map<String, Integer> addOnPrices =
        new LinkedHashMap<>();


    private final Map<String, String[]> availableAddOns =
        new LinkedHashMap<>();

    // Add-ons the cashier selected for each individual drink.
    // Key format: CATEGORY|Product  (so add-ons never leak to other drinks)
    private final Map<String, Set<String>> drinkAddOns =
        new LinkedHashMap<>();

    // ==========================================
    // 2. PENDING ITEM CLASS
    // ==========================================

    private class PendingItem {

        String name;
        String size;
        int unitPrice;
        int quantity;
        String drinkKey; // CATEGORY|Product, used to look up add-ons

        PendingItem(
            String name,
            String size,
            int unitPrice,
            int quantity,
            String drinkKey
        ) {
            this.name = name;
            this.size = size;
            this.unitPrice = unitPrice;
            this.quantity = quantity;
            this.drinkKey = drinkKey;
        }

        double getTotal() {
            return unitPrice * quantity;
        }
    }

    // ==========================================
    // 2B. ORDER DATA CLASSES
    // ==========================================

    // One confirmed line in the dashboard order.
    private static class OrderLine {

        final String productName;
        final String size;
        final String addOns;          // "Espresso, Pearls" or ""
        final int quantity;
        final BigDecimal basePrice;   // drink price per cup
        final BigDecimal addOnPrice;  // all add-ons per cup

        OrderLine(
            String productName,
            String size,
            String addOns,
            int quantity,
            BigDecimal basePrice,
            BigDecimal addOnPrice
        ) {
            this.productName = productName;
            this.size = size;
            this.addOns = addOns;
            this.quantity = quantity;
            this.basePrice = basePrice;
            this.addOnPrice = addOnPrice;
        }

        BigDecimal getUnitPrice() {
            return basePrice.add(addOnPrice);
        }

        // (drink + add-ons) x quantity, VAT included in the price
        BigDecimal getLineTotal() {
            return getUnitPrice().multiply(
                BigDecimal.valueOf(quantity)
            );
        }

        // All drinks qualify for the Senior/PWD discount.
        // Change this if some items should be excluded later.
        boolean isDiscountEligible() {
            return true;
        }

        // Example: Original Latte (12 OZ - Espresso, Pearls)
        String getDescription() {

            String details = size;

            if (!addOns.isEmpty()) {
                details += " - " + addOns;
            }

            return productName + " (" + details + ")";
        }
    }

    // The computed amounts shown in the cashout window.
    private static class OrderSummary {

        final BigDecimal subtotal; // VAT-exclusive amount
        final BigDecimal tax;      // 12% VAT charged
        final BigDecimal discount; // Senior/PWD discount
        final BigDecimal total;    // subtotal + tax - discount

        OrderSummary(
            BigDecimal subtotal,
            BigDecimal tax,
            BigDecimal discount,
            BigDecimal total
        ) {
            this.subtotal = subtotal;
            this.tax = tax;
            this.discount = discount;
            this.total = total;
        }
    }

    // Complete record of one paid transaction.
    // The receipt will be generated from this data.
    private static class TransactionRecord {

        final int transactionNumber;
        final LocalDateTime dateTime;
        final List<OrderLine> items;
        final BigDecimal subtotal;
        final BigDecimal tax;
        final BigDecimal discount;
        final BigDecimal total;
        final String discountType;   // NONE, SENIOR CARD, VALID ID
        final String paymentMethod;  // CASH or CREDIT
        final BigDecimal amountReceived;
        final BigDecimal change;

        TransactionRecord(
            int transactionNumber,
            LocalDateTime dateTime,
            List<OrderLine> items,
            OrderSummary summary,
            String discountType,
            String paymentMethod,
            BigDecimal amountReceived,
            BigDecimal change
        ) {
            this.transactionNumber = transactionNumber;
            this.dateTime = dateTime;
            this.items = items;
            this.subtotal = summary.subtotal;
            this.tax = summary.tax;
            this.discount = summary.discount;
            this.total = summary.total;
            this.discountType = discountType;
            this.paymentMethod = paymentMethod;
            this.amountReceived = amountReceived;
            this.change = change;
        }
    }

    // ==========================================
    // 4. INITIALIZE PRODUCTS
    // ==========================================

    private void initializeProducts() {

        products.put("CAFE LATTE", new String[] {
            "Original Latte",
            "Spanish Latte",
            "Strawberry Latte",
            "Matcha Latte",
            "Red Velvet"
        });

        products.put("AMERIKANO", new String[] {
            "Original",
            "Caramel Macchiato",
            "Hazelnut Brew",
            "Salted Caramel",
            "Black Brepuccino"
        });

        products.put("MILKTEA", new String[] {
            "Okinawa",
            "Wintermelon",
            "Cookies & Cream",
            "Green Matcha",
            "Red Velvet"
        });

        products.put("FRUIT SERIES", new String[] {
            "Green Apple",
            "Blueberry",
            "Kiwi",
            "Lychee",
            "Strawberry"
        });

        products.put("FRAPPE", new String[] {
            "Java Chip",
            "Vanilla Espresso",
            "Okinawa Espresso",
            "Cookies & Cream",
            "Strawberry"
        });
    }

    // ==========================================
    // 4B. INITIALIZE ADD-ONS
    // ==========================================

    private void initializeAddOns() {

        // Prices
        addOnPrices.put("Espresso", 5);
        addOnPrices.put("Pearls", 5);
        addOnPrices.put("Nata", 5);
        addOnPrices.put("Coffee Jelly", 5);
        addOnPrices.put("Popping Boba", 10);
        addOnPrices.put("Yakult", 15);

        // Compatibility per drink.
        // Edit this list to change which add-ons a drink can have.

        // CAFE LATTE
        defineAddOns("CAFE LATTE", "Original Latte",
            "Espresso", "Pearls", "Nata", "Coffee Jelly");
        defineAddOns("CAFE LATTE", "Spanish Latte",
            "Espresso", "Pearls", "Nata", "Coffee Jelly");
        defineAddOns("CAFE LATTE", "Strawberry Latte",
            "Pearls", "Nata", "Popping Boba");
        defineAddOns("CAFE LATTE", "Matcha Latte",
            "Espresso", "Pearls", "Nata");
        defineAddOns("CAFE LATTE", "Red Velvet",
            "Pearls", "Nata", "Coffee Jelly");

        // AMERIKANO
        defineAddOns("AMERIKANO", "Original",
            "Espresso", "Nata", "Coffee Jelly");
        defineAddOns("AMERIKANO", "Caramel Macchiato",
            "Espresso", "Pearls", "Coffee Jelly");
        defineAddOns("AMERIKANO", "Hazelnut Brew",
            "Espresso", "Pearls", "Coffee Jelly");
        defineAddOns("AMERIKANO", "Salted Caramel",
            "Espresso", "Pearls", "Coffee Jelly");
        defineAddOns("AMERIKANO", "Black Brepuccino",
            "Espresso", "Coffee Jelly");

        // MILKTEA
        defineAddOns("MILKTEA", "Okinawa",
            "Pearls", "Nata", "Coffee Jelly", "Popping Boba");
        defineAddOns("MILKTEA", "Wintermelon",
            "Pearls", "Nata", "Popping Boba", "Yakult");
        defineAddOns("MILKTEA", "Cookies & Cream",
            "Pearls", "Nata", "Coffee Jelly");
        defineAddOns("MILKTEA", "Green Matcha",
            "Espresso", "Pearls", "Nata");
        defineAddOns("MILKTEA", "Red Velvet",
            "Pearls", "Nata", "Popping Boba");

        // FRUIT SERIES
        defineAddOns("FRUIT SERIES", "Green Apple",
            "Nata", "Popping Boba", "Yakult");
        defineAddOns("FRUIT SERIES", "Blueberry",
            "Pearls", "Nata", "Popping Boba", "Yakult");
        defineAddOns("FRUIT SERIES", "Kiwi",
            "Nata", "Popping Boba", "Yakult");
        defineAddOns("FRUIT SERIES", "Lychee",
            "Nata", "Popping Boba", "Yakult");
        defineAddOns("FRUIT SERIES", "Strawberry",
            "Nata", "Popping Boba", "Yakult");

        // FRAPPE
        defineAddOns("FRAPPE", "Java Chip",
            "Espresso", "Pearls", "Nata", "Coffee Jelly");
        defineAddOns("FRAPPE", "Vanilla Espresso",
            "Espresso", "Pearls", "Coffee Jelly");
        defineAddOns("FRAPPE", "Okinawa Espresso",
            "Espresso", "Pearls", "Nata", "Coffee Jelly");
        defineAddOns("FRAPPE", "Cookies & Cream",
            "Pearls", "Nata", "Coffee Jelly", "Popping Boba");
        defineAddOns("FRAPPE", "Strawberry",
            "Pearls", "Nata", "Popping Boba", "Yakult");
    }

    private void defineAddOns(
        String category,
        String productName,
        String... addOns
    ) {
        availableAddOns.put(
            category + "|" + productName, addOns
        );
    }

    // ==========================================
    // 5. DASHBOARD UI
    // ==========================================

    private void createDashboardUI() {

        dashboardFrame = new JFrame("Cof/Tea Cafe POS");

        dashboardFrame.setDefaultCloseOperation(
            JFrame.EXIT_ON_CLOSE
        );

        dashboardFrame.setSize(1200, 700);
        dashboardFrame.setResizable(false);
        dashboardFrame.setLocationRelativeTo(null);

        dashboardFrame.setLayout(new BorderLayout());

        // HEADER

        JPanel headerPanel = new JPanel(
            new GridBagLayout()
        );

        headerPanel.setBackground(black);

        headerPanel.setPreferredSize(
            new Dimension(1200, 115)
        );

        headerPanel.setBorder(
            BorderFactory.createLineBorder(white, 3)
        );

        GridBagConstraints gbc = new GridBagConstraints();

        LogoPanel logo = new LogoPanel();

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.insets = new Insets(5, 20, 5, 20);
        gbc.anchor = GridBagConstraints.WEST;

        headerPanel.add(logo, gbc);

        JLabel greetingLabel = new JLabel(
            "GOOD DAY, CASHIER!"
        );

        greetingLabel.setFont(
            new Font("Arial", Font.BOLD, 26)
        );

        greetingLabel.setForeground(white);

        gbc.gridx = 1;
        gbc.insets = new Insets(5, 0, 5, 10);

        headerPanel.add(greetingLabel, gbc);

        gbc.gridx = 2;
        gbc.weightx = 1.0;

        headerPanel.add(
            Box.createHorizontalGlue(), gbc
        );

        JLabel titleLabel = new JLabel(
            "Cof/Tea Cafe POS"
        );

        titleLabel.setFont(
            new Font("Arial", Font.BOLD, 42)
        );

        titleLabel.setForeground(white);

        gbc.gridx = 3;
        gbc.weightx = 0;
        gbc.insets = new Insets(5, 10, 5, 55);

        headerPanel.add(titleLabel, gbc);

        dashboardFrame.add(
            headerPanel, BorderLayout.NORTH
        );

        // MAIN CONTENT

        JPanel mainPanel = new JPanel(
            new GridBagLayout()
        );

        mainPanel.setBackground(black);

        GridBagConstraints mainGbc =
            new GridBagConstraints();

        mainGbc.gridy = 0;
        mainGbc.weighty = 1.0;
        mainGbc.fill = GridBagConstraints.BOTH;

        // LEFT SIDE - ORDER TABLE

        JPanel leftPanel = new JPanel(
            new BorderLayout()
        );

        leftPanel.setBackground(black);

        leftPanel.add(
            createOrderTable(),
            BorderLayout.CENTER
        );

        mainGbc.gridx = 0;
        mainGbc.weightx = 0.55;
        mainGbc.insets = new Insets(3, 0, 0, 0);

        mainPanel.add(leftPanel, mainGbc);

        // RIGHT SIDE - CONTROLS

        JPanel rightPanel = new JPanel(
            new GridBagLayout()
        );

        rightPanel.setBackground(black);

        GridBagConstraints rightGbc =
            new GridBagConstraints();

        rightGbc.fill = GridBagConstraints.BOTH;
        rightGbc.weightx = 1.0;
        rightGbc.insets = new Insets(12, 15, 12, 15);

        // MENU AND REMOVE BUTTONS

        JPanel topButtonsPanel = new JPanel(
            new GridLayout(1, 2, 20, 0)
        );

        topButtonsPanel.setBackground(black);

        JButton menuButton = createDashboardButton(
            "MENU", blue
        );

        JButton removeButton = createDashboardButton(
            "REMOVE ITEM", red
        );

        menuButton.addActionListener(e -> {
            openMenuWindow();
        });

        removeButton.addActionListener(e -> {
            removeSelectedItem();
        });

        topButtonsPanel.add(menuButton);
        topButtonsPanel.add(removeButton);

        rightGbc.gridx = 0;
        rightGbc.gridy = 0;
        rightGbc.weighty = 0.25;

        rightPanel.add(topButtonsPanel, rightGbc);

        // CHECKOUT BUTTON

        JButton checkoutButton = createDashboardButton(
            "CHECK OUT", green
        );

        checkoutButton.addActionListener(e -> {
            openCheckoutWindow();
        });

        rightGbc.gridy = 1;
        rightGbc.weighty = 0.18;

        rightPanel.add(checkoutButton, rightGbc);

        // SUBTOTAL PANEL

        JPanel subtotalPanel = new JPanel(
            new BorderLayout()
        );

        subtotalPanel.setBackground(white);

        subtotalPanel.setBorder(
            BorderFactory.createLineBorder(
                Color.BLACK, 7
            )
        );

        subtotalLabel = new JLabel("SUBTOTAL: ₱0.00");

        subtotalLabel.setFont(
            new Font("Arial", Font.BOLD, 28)
        );

        subtotalLabel.setBorder(
            BorderFactory.createEmptyBorder(
                20, 20, 10, 10
            )
        );

        subtotalPanel.add(
            subtotalLabel, BorderLayout.NORTH
        );

        rightGbc.gridy = 2;
        rightGbc.weighty = 0.57;
        rightGbc.insets = new Insets(15, 15, 25, 15);

        rightPanel.add(subtotalPanel, rightGbc);

        mainGbc.gridx = 1;
        mainGbc.weightx = 0.45;
        mainGbc.insets = new Insets(0, 0, 0, 0);

        mainPanel.add(rightPanel, mainGbc);

        dashboardFrame.add(
            mainPanel, BorderLayout.CENTER
        );

        dashboardFrame.setVisible(true);
    }

    // ==========================================
    // 6. ORDER TABLE UI
    // ==========================================

    private JPanel createOrderTable() {

        JPanel panel = new JPanel(
            new BorderLayout()
        );

        panel.setBackground(white);

        String[] columns = {
            "ITEM", "QUANTITY", "PRICE"
        };

        orderTableModel = new DefaultTableModel(
            columns, 0
        ) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };

        orderTable = new JTable(orderTableModel);

        orderTable.setFont(
            new Font("Arial", Font.PLAIN, 18)
        );

        orderTable.setRowHeight(40);
        orderTable.setBackground(white);
        orderTable.setForeground(Color.BLACK);
        orderTable.setGridColor(Color.BLACK);
        orderTable.setShowGrid(true);

        orderTable.setSelectionBackground(
            new Color(200, 220, 240)
        );

        JTableHeader tableHeader =
            orderTable.getTableHeader();

        tableHeader.setFont(
            new Font("Arial", Font.BOLD, 22)
        );

        tableHeader.setBackground(gray);
        tableHeader.setForeground(Color.BLACK);

        tableHeader.setPreferredSize(
            new Dimension(0, 90)
        );

        tableHeader.setReorderingAllowed(false);

        orderTable.getColumnModel().getColumn(0)
            .setPreferredWidth(300);

        orderTable.getColumnModel().getColumn(1)
            .setPreferredWidth(110);

        orderTable.getColumnModel().getColumn(2)
            .setPreferredWidth(110);

        JScrollPane scrollPane = new JScrollPane(
            orderTable
        );

        scrollPane.setBorder(
            BorderFactory.createLineBorder(
                Color.BLACK, 2
            )
        );

        scrollPane.getViewport().setBackground(white);

        panel.add(
            scrollPane, BorderLayout.CENTER
        );

        return panel;
    }

    // ==========================================
    // 7. MENU WINDOW
    // ==========================================

    private void openMenuWindow() {

        // Prevent multiple menu windows

        if (menuFrame != null && menuFrame.isDisplayable()) {

            menuFrame.setVisible(true);
            menuFrame.toFront();

            return;
        }

        menuFrame = new JFrame("Cof/Tea Menu");

        menuFrame.setSize(1200, 700);

        menuFrame.setMinimumSize(
            new Dimension(900, 600)
        );

        menuFrame.setLocationRelativeTo(
            dashboardFrame
        );

        menuFrame.setDefaultCloseOperation(
            JFrame.DISPOSE_ON_CLOSE
        );

        menuFrame.setLayout(new BorderLayout());

        // MENU HEADER

        JPanel menuHeader = new JPanel(
            new GridBagLayout()
        );

        menuHeader.setBackground(black);

        menuHeader.setPreferredSize(
            new Dimension(1200, 110)
        );

        menuHeader.setBorder(
            BorderFactory.createLineBorder(
                white, 3
            )
        );

        GridBagConstraints headerGbc =
            new GridBagConstraints();

        // CART ICON

        JLabel cartIcon = new JLabel("🛒");

        cartIcon.setFont(
            new Font("Arial", Font.PLAIN, 55)
        );

        cartIcon.setForeground(white);

        headerGbc.gridx = 0;
        headerGbc.gridy = 0;
        headerGbc.insets = new Insets(5, 25, 5, 20);

        menuHeader.add(cartIcon, headerGbc);

        // ADDED ITEMS COUNTER

        addedItemsLabel = new JLabel(
            "ADDED ITEM/S: " + pendingOrder.size()
        );

        addedItemsLabel.setFont(
            new Font("Arial", Font.BOLD, 25)
        );

        addedItemsLabel.setForeground(white);

        headerGbc.gridx = 1;
        headerGbc.insets = new Insets(5, 15, 5, 15);

        menuHeader.add(
            addedItemsLabel, headerGbc
        );

        // SPACER

        headerGbc.gridx = 2;
        headerGbc.weightx = 1.0;

        menuHeader.add(
            Box.createHorizontalGlue(), headerGbc
        );

        // TITLE

        JLabel menuTitle = new JLabel(
            "COF/TEA MENU"
        );

        menuTitle.setFont(
            new Font("Arial", Font.BOLD, 42)
        );

        menuTitle.setForeground(white);

        headerGbc.gridx = 3;
        headerGbc.weightx = 0;
        headerGbc.insets = new Insets(5, 15, 5, 15);

        menuHeader.add(menuTitle, headerGbc);

        // SPACER

        headerGbc.gridx = 4;
        headerGbc.weightx = 1.0;

        menuHeader.add(
            Box.createHorizontalGlue(), headerGbc
        );

        // CATEGORY LABEL

        categoryLabel = new JLabel(
            "CAFE LATTE"
        );

        categoryLabel.setFont(
            new Font("Arial", Font.BOLD, 27)
        );

        categoryLabel.setForeground(white);

        headerGbc.gridx = 5;
        headerGbc.weightx = 0;
        headerGbc.insets = new Insets(5, 15, 5, 35);

        menuHeader.add(
            categoryLabel, headerGbc
        );

        menuFrame.add(
            menuHeader, BorderLayout.NORTH
        );

        // MENU BODY

        JPanel menuBody = new JPanel(
            new BorderLayout()
        );

        menuBody.setBackground(white);

        // CATEGORY SIDEBAR

        JPanel sidebar = new JPanel(
            new BorderLayout()
        );

        sidebar.setBackground(black);

        sidebar.setPreferredSize(
            new Dimension(310, 0)
        );

        JPanel categoryPanel = new JPanel(
            new GridLayout(5, 1, 0, 10)
        );

        categoryPanel.setBackground(black);

        categoryPanel.setBorder(
            BorderFactory.createEmptyBorder(
                12, 25, 15, 35
            )
        );

        // PRODUCT DISPLAY AREA

        productPanel = new JPanel(
            new GridLayout(0, 2, 8, 8)
        );

        productPanel.setBackground(white);

        productPanel.setBorder(
            BorderFactory.createEmptyBorder(
                8, 0, 8, 8
            )
        );

        // INITIAL CATEGORY

        String initialCategory = "CAFE LATTE";

        updateProductDisplay(
            initialCategory
        );

        // CATEGORY BUTTONS

        for (String category : products.keySet()) {

            JButton categoryButton =
                createCategoryButton(category);

            categoryButton.addActionListener(e -> {

                categoryLabel.setText(category);

                updateProductDisplay(category);

            });

            categoryPanel.add(categoryButton);
        }

        sidebar.add(
            categoryPanel, BorderLayout.NORTH
        );

        // ADD ORDER BUTTON

        JButton addOrderButton = createDashboardButton(
            "ADD ORDER", green
        );

        addOrderButton.setFont(
            new Font("Arial", Font.BOLD, 25)
        );

        addOrderButton.addActionListener(e -> {
            confirmOrder();
        });

        JPanel bottomButtonPanel = new JPanel(
            new BorderLayout()
        );

        bottomButtonPanel.setBackground(black);

        bottomButtonPanel.setBorder(
            BorderFactory.createEmptyBorder(
                15, 35, 25, 35
            )
        );

        bottomButtonPanel.add(
            addOrderButton, BorderLayout.CENTER
        );

        sidebar.add(
            bottomButtonPanel, BorderLayout.SOUTH
        );

        // SCROLLABLE PRODUCT DISPLAY

        JScrollPane productScrollPane =
            new JScrollPane(productPanel);

        productScrollPane.setBorder(
            BorderFactory.createEmptyBorder()
        );

        productScrollPane.setHorizontalScrollBarPolicy(
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        );

        productScrollPane.getVerticalScrollBar()
            .setUnitIncrement(20);

        productScrollPane.getViewport()
            .setBackground(white);

        // ADD COMPONENTS

        menuBody.add(
            sidebar, BorderLayout.WEST
        );

        menuBody.add(
            productScrollPane, BorderLayout.CENTER
        );

        menuFrame.add(
            menuBody, BorderLayout.CENTER
        );

        menuFrame.setVisible(true);
    }

    // ==========================================
    // 8. UPDATE PRODUCT DISPLAY
    // ==========================================

    private void updateProductDisplay(String category) {

        productPanel.removeAll();

        String[] categoryProducts =
            products.get(category);

        if (categoryProducts != null) {

            for (String productName : categoryProducts) {

                JPanel productCard =
                    createProductCard(category, productName);

                productPanel.add(productCard);
            }
        }

        productPanel.revalidate();
        productPanel.repaint();
    }

    // ==========================================
    // 9. PRODUCT CARD DESIGN
    // ==========================================

    private JPanel createProductCard(
        String category,
        String productName
    ) {

        // Unique id for this exact drink. Needed because some names
        // (Red Velvet, Strawberry, Cookies & Cream) exist in more
        // than one category.
        final String drinkKey = category + "|" + productName;

        JPanel card = new JPanel(
            new BorderLayout(5, 5)
        );

        card.setBackground(blue);

        card.setBorder(
            BorderFactory.createEmptyBorder(
                12, 12, 12, 12
            )
        );

        // PRODUCT TITLE

        JLabel nameLabel = new JLabel(
            productName.toUpperCase(),
            SwingConstants.CENTER
        );

        nameLabel.setFont(
            new Font("Arial", Font.BOLD, 23)
        );

        nameLabel.setForeground(Color.BLACK);

        card.add(
            nameLabel, BorderLayout.NORTH
        );

        // PRODUCT OPTIONS

        JPanel optionsPanel = new JPanel(
            new GridBagLayout()
        );

        optionsPanel.setBackground(blue);

        GridBagConstraints gbc =
            new GridBagConstraints();

        gbc.insets = new Insets(3, 4, 3, 4);
        gbc.fill = GridBagConstraints.BOTH;

        // SIZE OPTIONS

        JPanel sizesPanel = new JPanel(
            new GridLayout(1, 3, 5, 0)
        );

        sizesPanel.setBackground(blue);

        String[] sizes = {
            "12 OZ", "16 OZ", "22 OZ"
        };

        int[] prices = {
            39, 49, 59
        };

        // QUANTITY AND ADD-ONS

        JPanel quantityPanel = new JPanel(
            new GridLayout(3, 1, 0, 5)
        );

        quantityPanel.setBackground(blue);

        JLabel quantityLabel = new JLabel(
            "Quantity:",
            SwingConstants.CENTER
        );

        quantityLabel.setFont(
            new Font("Arial", Font.BOLD, 19)
        );

        JSpinner quantitySpinner = new JSpinner(
            new SpinnerNumberModel(1, 1, 99, 1)
        );

        quantitySpinner.setFont(
            new Font("Arial", Font.BOLD, 24)
        );

        quantitySpinner.setPreferredSize(
            new Dimension(125, 55)
        );

        // ADD-ONS BUTTON

        JButton addOnsButton = new JButton(
            "ADD-ONS"
        );

        addOnsButton.setFont(
            new Font("Arial", Font.BOLD, 21)
        );

        addOnsButton.setForeground(white);
        addOnsButton.setBackground(green);

        addOnsButton.setBorderPainted(false);
        addOnsButton.setFocusPainted(false);
        addOnsButton.setOpaque(true);

        addOnsButton.setCursor(
            new Cursor(Cursor.HAND_CURSOR)
        );

        // Show the saved add-on state for this drink
        // (e.g. when coming back from another category)
        refreshAddOnsButton(addOnsButton, drinkKey);

        addOnsButton.addActionListener(e -> {
            openAddOnsDialog(
                productName, drinkKey, addOnsButton
            );
        });

        quantityPanel.add(quantityLabel);
        quantityPanel.add(quantitySpinner);
        quantityPanel.add(addOnsButton);

        // SIZE BUTTONS

        for (int i = 0; i < sizes.length; i++) {

            final String selectedSize = sizes[i];
            final int selectedPrice = prices[i];

            final String itemKey =
                drinkKey + "|" + selectedSize;

            JPanel sizeOption = new JPanel(
                new GridLayout(3, 1, 0, 3)
            );

            sizeOption.setBackground(blue);

            JLabel sizeLabel = new JLabel(
                selectedSize,
                SwingConstants.CENTER
            );

            sizeLabel.setFont(
                new Font("Arial", Font.BOLD, 19)
            );

            JLabel priceLabel = new JLabel(
                "(₱" + selectedPrice + ")",
                SwingConstants.CENTER
            );

            priceLabel.setFont(
                new Font("Arial", Font.BOLD, 18)
            );

            // Larger circular selection button

            CircleSelectionButton sizeButton =
                new CircleSelectionButton();

            sizeButton.setPreferredSize(
                new Dimension(66, 66)
            );

            sizeButton.setMinimumSize(
                new Dimension(66, 66)
            );

            sizeButton.setMaximumSize(
                new Dimension(66, 66)
            );

            sizeButton.setCursor(
                new Cursor(Cursor.HAND_CURSOR)
            );

            // Restore selection when switching categories

            PendingItem existingItem =
                pendingOrder.get(itemKey);

            if (existingItem != null) {

                sizeButton.setSelected(true);

                quantitySpinner.setValue(
                    existingItem.quantity
                );
            }

            // SELECT OR DESELECT SIZE

            sizeButton.addActionListener(e -> {

                if (sizeButton.isSelected()) {

                    // Remove selection

                    sizeButton.setSelected(false);

                    pendingOrder.remove(itemKey);

                } else {

                    // Add selection

                    sizeButton.setSelected(true);

                    int quantity =
                        (Integer) quantitySpinner.getValue();

                    PendingItem newItem = new PendingItem(
                        productName,
                        selectedSize,
                        selectedPrice,
                        quantity,
                        drinkKey
                    );

                    pendingOrder.put(
                        itemKey,
                        newItem
                    );
                }

                updateAddedItemsCounter();
            });

            // UPDATE QUANTITY IF ITEM IS ALREADY SELECTED

            quantitySpinner.addChangeListener(e -> {

                PendingItem selectedItem =
                    pendingOrder.get(itemKey);

                if (selectedItem != null) {

                    selectedItem.quantity =
                        (Integer) quantitySpinner.getValue();

                }
            });

            sizeOption.add(sizeLabel);
            sizeOption.add(priceLabel);
            sizeOption.add(sizeButton);

            sizesPanel.add(sizeOption);
        }

        // ADD SIZE AND QUANTITY PANELS

        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 0.7;
        gbc.weighty = 1.0;

        optionsPanel.add(sizesPanel, gbc);

        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.weightx = 0.3;

        optionsPanel.add(quantityPanel, gbc);

        card.add(
            optionsPanel, BorderLayout.CENTER
        );

        card.setPreferredSize(
            new Dimension(400, 220)
        );

        return card;
    }

    // ==========================================
    // 9B. ADD-ONS WINDOW
    // ==========================================

    private void openAddOnsDialog(
        String productName,
        String drinkKey,
        JButton addOnsButton
    ) {

        String[] available = availableAddOns.get(drinkKey);

        if (available == null || available.length == 0) {

            JOptionPane.showMessageDialog(
                menuFrame,
                "No add-ons are available for "
                    + productName + ".",
                "No Add-ons",
                JOptionPane.INFORMATION_MESSAGE
            );

            return;
        }

        Set<String> current = drinkAddOns.getOrDefault(
            drinkKey, Collections.<String>emptySet()
        );

        JDialog dialog = new JDialog(menuFrame, true);

        dialog.setUndecorated(true);

        // Transparent corners (falls back to square if unsupported)
        try {
            dialog.setBackground(new Color(0, 0, 0, 0));
        } catch (UnsupportedOperationException ex) {
            // ignore
        }

        // OUTER ROUNDED WINDOW

        RoundedPanel root = new RoundedPanel(
            white, Color.BLACK, 3, 30
        );

        root.setLayout(new BorderLayout(0, 8));

        root.setBorder(
            BorderFactory.createEmptyBorder(8, 14, 16, 14)
        );

        // HEADER

        RoundedPanel header = new RoundedPanel(
            black, null, 0, 24
        );

        header.setLayout(new GridBagLayout());

        header.setPreferredSize(new Dimension(0, 70));

        JLabel title = new JLabel("ADD-ONS");

        title.setFont(new Font("Arial", Font.BOLD, 30));
        title.setForeground(white);

        header.add(title, new GridBagConstraints());

        root.add(header, BorderLayout.NORTH);

        // ADD-ON ROWS (only add-ons compatible with this drink)

        JPanel rowsPanel = new JPanel(
            new GridLayout(available.length, 1, 0, 4)
        );

        rowsPanel.setOpaque(false);

        rowsPanel.setBorder(
            BorderFactory.createEmptyBorder(10, 20, 10, 12)
        );

        Map<String, PillToggle> toggles = new LinkedHashMap<>();

        for (String addOn : available) {

            JPanel row = new JPanel(new BorderLayout());

            row.setOpaque(false);

            row.setPreferredSize(new Dimension(0, 62));

            JLabel label = new JLabel(
                addOn.toUpperCase()
                    + " ₱" + addOnPrices.get(addOn) + ":"
            );

            label.setFont(new Font("Arial", Font.BOLD, 26));
            label.setForeground(Color.BLACK);

            PillToggle toggle = new PillToggle();

            toggle.setSelected(current.contains(addOn));

            toggles.put(addOn, toggle);

            JPanel toggleHolder = new JPanel(
                new GridBagLayout()
            );

            toggleHolder.setOpaque(false);
            toggleHolder.add(toggle);

            row.add(label, BorderLayout.CENTER);
            row.add(toggleHolder, BorderLayout.EAST);

            rowsPanel.add(row);
        }

        root.add(rowsPanel, BorderLayout.CENTER);

        // BUTTONS

        JPanel buttonsPanel = new JPanel(
            new GridLayout(1, 2, 28, 0)
        );

        buttonsPanel.setOpaque(false);

        buttonsPanel.setPreferredSize(new Dimension(0, 80));

        buttonsPanel.setBorder(
            BorderFactory.createEmptyBorder(0, 0, 0, 0)
        );

        RoundedButton addButton =
            new RoundedButton("ADD", green);

        RoundedButton cancelButton =
            new RoundedButton("CANCEL", red);

        addButton.addActionListener(e -> {

            Set<String> chosen = new LinkedHashSet<>();

            // Keep the same order as shown in the window
            for (Map.Entry<String, PillToggle> entry
                : toggles.entrySet()) {

                if (entry.getValue().isSelected()) {
                    chosen.add(entry.getKey());
                }
            }

            if (chosen.isEmpty()) {
                drinkAddOns.remove(drinkKey);
            } else {
                drinkAddOns.put(drinkKey, chosen);
            }

            refreshAddOnsButton(addOnsButton, drinkKey);

            dialog.dispose();
        });

        cancelButton.addActionListener(e -> dialog.dispose());

        buttonsPanel.add(addButton);
        buttonsPanel.add(cancelButton);

        root.add(buttonsPanel, BorderLayout.SOUTH);

        dialog.setContentPane(root);

        root.setPreferredSize(
            new Dimension(
                420,
                8 + 70 + 8 + 20 + (available.length * 66)
                    + 8 + 80 + 16
            )
        );

        dialog.pack();

        dialog.setLocationRelativeTo(menuFrame);

        dialog.setVisible(true);
    }

    // Updates the card's ADD-ONS button so the cashier can see
    // whether this drink has add-ons (button turns dark + tooltip).
    private void refreshAddOnsButton(
        JButton button,
        String drinkKey
    ) {

        Set<String> selected = drinkAddOns.get(drinkKey);

        if (selected == null || selected.isEmpty()) {

            button.setBackground(green);
            button.setToolTipText(null);

        } else {

            button.setBackground(black);
            button.setToolTipText(
                "Add-ons: " + String.join(", ", selected)
            );
        }
    }

    // ==========================================
    // 10. UPDATE ADDED ITEMS COUNTER
    // ==========================================

    private void updateAddedItemsCounter() {

        if (addedItemsLabel != null) {

            addedItemsLabel.setText(
                "ADDED ITEM/S: " + pendingOrder.size()
            );
        }
    }

    // ==========================================
    // 11. CONFIRM ORDER
    // ==========================================

    private void confirmOrder() {

        if (pendingOrder.isEmpty()) {

            JOptionPane.showMessageDialog(
                menuFrame,
                "Please select at least one item.",
                "No Items Selected",
                JOptionPane.WARNING_MESSAGE
            );

            return;
        }

        int confirmation = JOptionPane.showConfirmDialog(
            menuFrame,
            "Add all selected items to the order?",
            "Confirm Order",
            JOptionPane.YES_NO_OPTION
        );

        if (confirmation != JOptionPane.YES_OPTION) {
            return;
        }

        // Transfer every selected product and size
        // into the dashboard order table.

        for (PendingItem item : pendingOrder.values()) {

            // Add-ons chosen for THIS drink only
            Set<String> addOns = drinkAddOns.getOrDefault(
                item.drinkKey, Collections.<String>emptySet()
            );

            // Add-on price per cup
            int addOnPerCup = 0;

            for (String addOn : addOns) {
                addOnPerCup += addOnPrices.get(addOn);
            }

            // (base price + add-ons) x quantity is computed by OrderLine
            OrderLine line = new OrderLine(
                item.name,
                item.size,
                String.join(", ", addOns),
                item.quantity,
                BigDecimal.valueOf(item.unitPrice),
                BigDecimal.valueOf(addOnPerCup)
            );

            orderLines.add(line);

            orderTableModel.addRow(new Object[] {
                line.getDescription(),
                line.quantity,
                peso(line.getLineTotal())
            });
        }

        updateSubtotal();

        // Clear pending selections after successful transfer

        pendingOrder.clear();
        drinkAddOns.clear();

        updateAddedItemsCounter();

        // Close menu and return to dashboard

        menuFrame.dispose();
        menuFrame = null;
    }

    // ==========================================
    // 12. REMOVE SELECTED DASHBOARD ITEM
    // ==========================================

    private void removeSelectedItem() {

        int selectedRow = orderTable.getSelectedRow();

        if (selectedRow == -1) {

            JOptionPane.showMessageDialog(
                dashboardFrame,
                "Please select an item to remove.",
                "No Item Selected",
                JOptionPane.WARNING_MESSAGE
            );

            return;
        }

        orderLines.remove(selectedRow);

        orderTableModel.removeRow(selectedRow);

        updateSubtotal();
    }

    // ==========================================
    // 13. UPDATE SUBTOTAL
    // ==========================================

    private void updateSubtotal() {

        BigDecimal orderTotal = BigDecimal.ZERO;

        for (OrderLine line : orderLines) {
            orderTotal = orderTotal.add(line.getLineTotal());
        }

        subtotalLabel.setText(
            "SUBTOTAL: " + peso(orderTotal)
        );
    }

    // ==========================================
    // 13B. CASHOUT (CHECK OUT) WINDOW
    // ==========================================

    private void openCheckoutWindow() {

        if (orderLines.isEmpty()) {

            JOptionPane.showMessageDialog(
                dashboardFrame,
                "There are no items in the order.",
                "Empty Order",
                JOptionPane.WARNING_MESSAGE
            );

            return;
        }

        JDialog dialog = new JDialog(dashboardFrame, true);

        dialog.setUndecorated(true);

        // Transparent corners (falls back to square if unsupported)
        try {
            dialog.setBackground(new Color(0, 0, 0, 0));
        } catch (UnsupportedOperationException ex) {
            // ignore
        }

        // OUTER ROUNDED WINDOW

        RoundedPanel root = new RoundedPanel(
            white, Color.BLACK, 3, 30
        );

        root.setLayout(new BorderLayout(0, 8));

        root.setBorder(
            BorderFactory.createEmptyBorder(8, 14, 16, 14)
        );

        // HEADER

        RoundedPanel header = new RoundedPanel(
            black, null, 0, 24
        );

        header.setLayout(new GridBagLayout());

        header.setPreferredSize(new Dimension(440, 70));

        JLabel title = new JLabel("CHECK OUT");

        title.setFont(new Font("Arial", Font.BOLD, 30));
        title.setForeground(white);

        header.add(title, new GridBagConstraints());

        root.add(header, BorderLayout.NORTH);

        // VALUE LABELS (filled in by refreshSummary)

        JLabel subtotalValue = new JLabel();
        JLabel taxValue = new JLabel();
        JLabel discountValue = new JLabel();
        JLabel totalValue = new JLabel();
        JLabel changeValue = new JLabel();

        changeValue.setForeground(green);

        // INPUTS

        JComboBox<String> seniorCombo = new JComboBox<>(
            new String[] { "SENIOR CARD", "VALID ID", "NO" }
        );

        styleComboBox(seniorCombo);

        // Never apply the discount unless the cashier chooses it
        seniorCombo.setSelectedItem("NO");

        JComboBox<String> paymentCombo = new JComboBox<>(
            new String[] { "CASH", "CREDIT" }
        );

        styleComboBox(paymentCombo);

        JTextField paymentField = new JTextField();

        paymentField.setFont(new Font("Arial", Font.BOLD, 26));
        paymentField.setHorizontalAlignment(SwingConstants.CENTER);
        paymentField.setBorder(null);
        paymentField.setBackground(white);
        paymentField.setForeground(Color.BLACK);
        paymentField.setCaretColor(Color.BLACK);

        // Only digits and up to 2 decimal places
        ((AbstractDocument) paymentField.getDocument())
            .setDocumentFilter(new AmountFilter());

        // BODY

        JPanel body = new JPanel(new GridBagLayout());

        body.setOpaque(false);

        body.setBorder(
            BorderFactory.createEmptyBorder(8, 12, 8, 12)
        );

        GridBagConstraints g = new GridBagConstraints();

        g.gridx = 0;
        g.weightx = 1.0;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.anchor = GridBagConstraints.NORTHWEST;

        int row = 0;

        g.insets = new Insets(4, 4, 4, 4);

        g.gridy = row++;
        body.add(createValueRow("SUBTOTAL:", subtotalValue), g);

        g.gridy = row++;
        body.add(createValueRow("TAX:", taxValue), g);

        g.gridy = row++;
        body.add(createValueRow("TOTAL AMOUNT:", totalValue), g);

        g.gridy = row++;
        body.add(
            createValueRow("SENIOR DISCOUNT:", discountValue), g
        );

        g.gridy = row++;
        g.insets = new Insets(2, 4, 10, 4);
        body.add(createInputBox(seniorCombo, 56), g);

        g.gridy = row++;
        g.insets = new Insets(4, 4, 4, 4);
        body.add(
            createValueRow("PAYMENT METHOD:", new JLabel()), g
        );

        g.gridy = row++;
        g.insets = new Insets(2, 4, 10, 4);
        body.add(createInputBox(paymentCombo, 56), g);

        g.gridy = row++;
        g.insets = new Insets(4, 4, 4, 4);
        body.add(
            createValueRow("ENTER PAYMENT:", changeValue), g
        );

        g.gridy = row++;
        g.insets = new Insets(2, 4, 6, 4);
        body.add(createInputBox(paymentField, 56), g);

        root.add(body, BorderLayout.CENTER);

        // BUTTONS

        JPanel buttonsPanel = new JPanel(
            new GridLayout(1, 2, 24, 0)
        );

        buttonsPanel.setOpaque(false);

        buttonsPanel.setPreferredSize(new Dimension(0, 80));

        RoundedButton processButton = new RoundedButton(
            "<html><center>PROCESS<br>PAYMENT</center></html>",
            green
        );

        processButton.setFont(new Font("Arial", Font.BOLD, 24));

        RoundedButton cancelButton =
            new RoundedButton("CANCEL", red);

        cancelButton.setFont(new Font("Arial", Font.BOLD, 24));

        buttonsPanel.add(processButton);
        buttonsPanel.add(cancelButton);

        root.add(buttonsPanel, BorderLayout.SOUTH);

        // ---- LIVE CALCULATION ----

        final OrderSummary[] summary = new OrderSummary[1];

        // Shows the change while the cashier types
        Runnable updateChange = () -> {

            BigDecimal received =
                parseAmount(paymentField.getText());

            if (received != null
                && summary[0] != null
                && received.compareTo(summary[0].total) >= 0) {

                changeValue.setText(
                    "CHANGE: "
                        + peso(received.subtract(summary[0].total))
                );

            } else {

                changeValue.setText("");
            }
        };

        // Recalculates everything from the current order and
        // the Senior/PWD selection
        Runnable refreshSummary = () -> {

            boolean eligible =
                !"NO".equals(seniorCombo.getSelectedItem());

            OrderSummary s =
                calculateSummary(orderLines, eligible);

            summary[0] = s;

            subtotalValue.setText(peso(s.subtotal));
            taxValue.setText(peso(s.tax));
            totalValue.setText(peso(s.total));

            discountValue.setText(
                s.discount.signum() > 0
                    ? "-" + peso(s.discount)
                    : peso(s.discount)
            );

            // Card payments are always the exact total
            if ("CREDIT".equals(paymentCombo.getSelectedItem())) {
                paymentField.setText(s.total.toPlainString());
            }

            updateChange.run();
        };

        refreshSummary.run();

        // Senior/PWD changed -> recalculate everything
        seniorCombo.addActionListener(e -> refreshSummary.run());

        // Payment method changed
        paymentCombo.addActionListener(e -> {

            boolean card =
                "CREDIT".equals(paymentCombo.getSelectedItem());

            paymentField.setEditable(!card);

            if (card) {

                paymentField.setText(
                    summary[0].total.toPlainString()
                );

            } else {

                paymentField.setText("");
                paymentField.requestFocusInWindow();
            }

            updateChange.run();
        });

        // Typing a cash amount updates the change preview
        paymentField.getDocument().addDocumentListener(
            new DocumentListener() {

                @Override
                public void insertUpdate(DocumentEvent e) {
                    updateChange.run();
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    updateChange.run();
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    updateChange.run();
                }
            }
        );

        // ---- PROCESS PAYMENT ----

        processButton.addActionListener(e -> {

            OrderSummary s = summary[0];

            boolean card =
                "CREDIT".equals(paymentCombo.getSelectedItem());

            // Card = exact total, cash = what the cashier typed
            BigDecimal received = card
                ? s.total
                : parseAmount(paymentField.getText());

            if (received == null) {

                JOptionPane.showMessageDialog(
                    dialog,
                    "Please enter the amount received "
                        + "from the customer.",
                    "No Payment Entered",
                    JOptionPane.WARNING_MESSAGE
                );

                paymentField.requestFocusInWindow();

                return;
            }

            // Insufficient: reject and let the cashier try again
            if (received.compareTo(s.total) < 0) {

                JOptionPane.showMessageDialog(
                    dialog,
                    "Insufficient payment!\n\n"
                        + "Total Amount: " + peso(s.total) + "\n"
                        + "Amount Received: " + peso(received) + "\n"
                        + "Short by: "
                        + peso(s.total.subtract(received)),
                    "Insufficient Payment",
                    JOptionPane.WARNING_MESSAGE
                );

                paymentField.selectAll();
                paymentField.requestFocusInWindow();

                return;
            }

            // Exact amount -> change is 0.00
            // Excess amount -> change = received - total
            BigDecimal change =
                money(received.subtract(s.total));

            String selectedDiscount =
                (String) seniorCombo.getSelectedItem();

            String discountType =
                "NO".equals(selectedDiscount)
                    ? "NONE"
                    : selectedDiscount;

            String method = card ? "CREDIT" : "CASH";

            // Payment is valid: finalize the transaction
            TransactionRecord record = completeTransaction(
                s, discountType, method, received, change
            );

            dialog.dispose();

            // Save the .txt receipt automatically (payment is already
            // validated and the transaction is already finalized here)
            saveReceiptFileAutomatically(record);

            // The receipt is built from the saved transaction data
            openReceiptWindow(record);
        });

        // Pressing Enter in the amount field processes the payment
        paymentField.addActionListener(
            e -> processButton.doClick()
        );

        cancelButton.addActionListener(e -> dialog.dispose());

        dialog.setContentPane(root);

        dialog.pack();

        dialog.setLocationRelativeTo(dashboardFrame);

        SwingUtilities.invokeLater(
            () -> paymentField.requestFocusInWindow()
        );

        dialog.setVisible(true);
    }

    // ------------------------------------------
    // VAT AND SENIOR/PWD CALCULATION
    // ------------------------------------------
    //
    // VAT and the discount are kept separate:
    //
    //  Regular items (no ID):
    //      VAT-exclusive amount = price / 1.12   (if prices include VAT)
    //      VAT                  = 12% of that amount
    //
    //  Eligible items (valid Senior/PWD ID):
    //      VAT-exempt, so VAT = 0
    //      discount = 20% of the VAT-exclusive amount
    //
    //  Total = subtotal + VAT - discount
    //
    private OrderSummary calculateSummary(
        List<OrderLine> lines,
        boolean seniorOrPwd
    ) {

        BigDecimal regularGross = BigDecimal.ZERO;
        BigDecimal eligibleGross = BigDecimal.ZERO;

        for (OrderLine line : lines) {

            BigDecimal lineAmount = line.getLineTotal();

            if (seniorOrPwd && line.isDiscountEligible()) {
                eligibleGross = eligibleGross.add(lineAmount);
            } else {
                regularGross = regularGross.add(lineAmount);
            }
        }

        // Regular items: 12% VAT applies
        BigDecimal regularNet;
        BigDecimal regularVat;

        if (PRICES_INCLUDE_VAT) {

            regularNet = money(
                regularGross.divide(
                    VAT_DIVISOR, 2, RoundingMode.HALF_UP
                )
            );

            // Remainder, so net + VAT always equals the price paid
            regularVat = money(regularGross.subtract(regularNet));

        } else {

            regularNet = money(regularGross);

            regularVat = money(
                regularGross.multiply(VAT_RATE)
            );
        }

        // Eligible items: VAT-exempt, discount on the VAT-exclusive amount
        BigDecimal eligibleNet;

        if (PRICES_INCLUDE_VAT) {

            eligibleNet = money(
                eligibleGross.divide(
                    VAT_DIVISOR, 2, RoundingMode.HALF_UP
                )
            );

        } else {

            eligibleNet = money(eligibleGross);
        }

        BigDecimal discount = money(
            eligibleNet.multiply(SENIOR_PWD_DISCOUNT_RATE)
        );

        BigDecimal subtotal = money(regularNet.add(eligibleNet));

        BigDecimal tax = regularVat;

        BigDecimal total = money(
            subtotal.add(tax).subtract(discount)
        );

        return new OrderSummary(subtotal, tax, discount, total);
    }

    // ------------------------------------------
    // FINALIZE A PAID TRANSACTION
    // ------------------------------------------

    private TransactionRecord completeTransaction(
        OrderSummary summary,
        String discountType,
        String paymentMethod,
        BigDecimal amountReceived,
        BigDecimal change
    ) {

        transactionCounter++;

        // Copy the order so the record stays complete after
        // the dashboard order is cleared
        TransactionRecord record = new TransactionRecord(
            transactionCounter,
            LocalDateTime.now(),
            new ArrayList<>(orderLines),
            summary,
            discountType,
            paymentMethod,
            amountReceived,
            change
        );

        transactions.add(record);

        // The receipt will be built from this
        lastTransaction = record;

        // Start a fresh order
        orderLines.clear();
        orderTableModel.setRowCount(0);

        updateSubtotal();

        return record;
    }

    // ------------------------------------------
    // CASHOUT WINDOW HELPERS
    // ------------------------------------------

    // "LABEL:                 ₱0.00"
    private JPanel createValueRow(
        String labelText,
        JLabel valueLabel
    ) {

        JPanel row = new JPanel(new BorderLayout());

        row.setOpaque(false);

        JLabel label = new JLabel(labelText);

        label.setFont(new Font("Arial", Font.BOLD, 24));
        label.setForeground(Color.BLACK);

        valueLabel.setFont(new Font("Arial", Font.BOLD, 24));
        valueLabel.setHorizontalAlignment(SwingConstants.RIGHT);

        row.add(label, BorderLayout.WEST);
        row.add(valueLabel, BorderLayout.EAST);

        return row;
    }

    // White rounded input box with a black outline
    private RoundedPanel createInputBox(
        JComponent inner,
        int height
    ) {

        RoundedPanel box = new RoundedPanel(
            white, Color.BLACK, 3, 30
        );

        box.setLayout(new BorderLayout());

        box.setBorder(
            BorderFactory.createEmptyBorder(4, 14, 4, 14)
        );

        box.add(inner, BorderLayout.CENTER);

        box.setPreferredSize(new Dimension(0, height));

        return box;
    }

    // Flat, centered combo box that sits inside a rounded box
    private void styleComboBox(JComboBox<String> combo) {

        // The UI must be set first, otherwise it resets our styling
        combo.setUI(new BasicComboBoxUI() {

            @Override
            protected JButton createArrowButton() {

                BasicArrowButton arrow = new BasicArrowButton(
                    BasicArrowButton.SOUTH,
                    white, white, Color.BLACK, white
                );

                arrow.setBorder(
                    BorderFactory.createEmptyBorder()
                );

                return arrow;
            }
        });

        combo.setFont(new Font("Arial", Font.BOLD, 24));
        combo.setBackground(white);
        combo.setForeground(Color.BLACK);
        combo.setBorder(null);

        // Prevents the blue "focused" highlight on the shown value
        combo.setFocusable(false);

        combo.setCursor(new Cursor(Cursor.HAND_CURSOR));

        combo.setRenderer(new DefaultListCellRenderer() {

            @Override
            public Component getListCellRendererComponent(
                JList<?> list,
                Object value,
                int index,
                boolean isSelected,
                boolean cellHasFocus
            ) {

                JLabel label = (JLabel)
                    super.getListCellRendererComponent(
                        list, value, index,
                        isSelected, cellHasFocus
                    );

                label.setHorizontalAlignment(SwingConstants.CENTER);
                label.setFont(new Font("Arial", Font.BOLD, 24));

                label.setBorder(
                    BorderFactory.createEmptyBorder(6, 6, 6, 6)
                );

                return label;
            }
        });
    }

    // ------------------------------------------
    // MONEY HELPERS
    // ------------------------------------------

    // Always 2 decimal places, standard rounding
    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    // "₱1,234.50"
    private static String peso(BigDecimal amount) {
        return "₱" + String.format(Locale.US, "%,.2f", amount);
    }

    // Returns null if the text is empty or not a number
    private static BigDecimal parseAmount(String text) {

        if (text == null) {
            return null;
        }

        String trimmed = text.trim();

        if (trimmed.isEmpty() || trimmed.equals(".")) {
            return null;
        }

        try {
            return money(new BigDecimal(trimmed));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // Allows only digits and up to 2 decimal places
    private static class AmountFilter extends DocumentFilter {

        private boolean isValid(String text) {
            return text.matches("\\d{0,7}(\\.\\d{0,2})?");
        }

        @Override
        public void insertString(
            FilterBypass fb,
            int offset,
            String string,
            AttributeSet attr
        ) throws BadLocationException {

            String current = fb.getDocument().getText(
                0, fb.getDocument().getLength()
            );

            String next = new StringBuilder(current)
                .insert(offset, string)
                .toString();

            if (isValid(next)) {
                super.insertString(fb, offset, string, attr);
            }
        }

        @Override
        public void replace(
            FilterBypass fb,
            int offset,
            int length,
            String text,
            AttributeSet attrs
        ) throws BadLocationException {

            String current = fb.getDocument().getText(
                0, fb.getDocument().getLength()
            );

            String next = new StringBuilder(current)
                .replace(
                    offset, offset + length,
                    text == null ? "" : text
                )
                .toString();

            if (isValid(next)) {
                super.replace(fb, offset, length, text, attrs);
            }
        }
    }

    // ==========================================
    // 13C. RECEIPT
    // ==========================================

    // Shows the receipt of a paid transaction.
    // Only called after payment has been validated and saved.
    private void openReceiptWindow(TransactionRecord record) {

        JDialog dialog = new JDialog(dashboardFrame, "Receipt", true);

        dialog.setDefaultCloseOperation(
            JDialog.DISPOSE_ON_CLOSE
        );

        dialog.getContentPane().setBackground(black);

        dialog.setLayout(new BorderLayout());

        ReceiptPanel receipt = new ReceiptPanel(record);

        JScrollPane scrollPane = new JScrollPane(receipt);

        scrollPane.setBorder(
            BorderFactory.createEmptyBorder(16, 16, 8, 16)
        );

        scrollPane.setBackground(black);
        scrollPane.getViewport().setBackground(black);

        scrollPane.setHorizontalScrollBarPolicy(
            JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        );

        scrollPane.getVerticalScrollBar().setUnitIncrement(20);

        // Tall receipts scroll instead of growing past the screen
        Dimension receiptSize = receipt.getPreferredSize();

        scrollPane.setPreferredSize(
            new Dimension(
                receiptSize.width + 32 + 20,
                Math.min(receiptSize.height, 600) + 24
            )
        );

        dialog.add(scrollPane, BorderLayout.CENTER);

        // DONE button (outside the receipt, so it is never printed)

        RoundedButton doneButton =
            new RoundedButton("DONE", green);

        doneButton.setFont(new Font("Arial", Font.BOLD, 24));

        doneButton.addActionListener(e -> dialog.dispose());

        JPanel buttonPanel = new JPanel(new BorderLayout());

        buttonPanel.setBackground(black);

        buttonPanel.setBorder(
            BorderFactory.createEmptyBorder(8, 16, 16, 16)
        );

        buttonPanel.setPreferredSize(new Dimension(0, 76));

        buttonPanel.add(doneButton, BorderLayout.CENTER);

        dialog.add(buttonPanel, BorderLayout.SOUTH);

        dialog.pack();

        dialog.setLocationRelativeTo(dashboardFrame);

        dialog.setVisible(true);
    }

    // Draws the receipt following the reference layout.
    // paintReceipt() draws onto any Graphics2D (screen, image or
    // printer), so the same drawing can be reused for printing.
    private class ReceiptPanel extends JPanel {

        private final int receiptWidth = 440;

        private final TransactionRecord transaction;

        private final int receiptHeight;

        ReceiptPanel(TransactionRecord transaction) {

            this.transaction = transaction;

            setOpaque(false);

            // Measure the receipt once so the panel is the right height
            BufferedImage scratch = new BufferedImage(
                1, 1, BufferedImage.TYPE_INT_ARGB
            );

            Graphics2D g2 = scratch.createGraphics();

            receiptHeight = paintReceipt(g2);

            g2.dispose();

            setPreferredSize(
                new Dimension(receiptWidth + 8, receiptHeight + 8)
            );
        }

        @Override
        protected void paintComponent(Graphics g) {

            Graphics2D g2 = (Graphics2D) g.create();

            g2.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
            );

            // Keep the receipt centered if the window is wider
            int x = Math.max(4, (getWidth() - receiptWidth) / 2);

            g2.translate(x, 4);

            // Paper with a rounded black outline
            g2.setColor(Color.WHITE);

            g2.fillRoundRect(
                2, 2, receiptWidth - 4, receiptHeight - 4, 34, 34
            );

            g2.setColor(Color.BLACK);
            g2.setStroke(new BasicStroke(3f));

            g2.drawRoundRect(
                2, 2, receiptWidth - 4, receiptHeight - 4, 34, 34
            );

            paintReceipt(g2);

            g2.dispose();
        }

        // Draws all receipt text and returns the total receipt height.
        int paintReceipt(Graphics2D g2) {

            g2.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
            );

            g2.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON
            );

            g2.setRenderingHint(
                RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_ON
            );

            g2.setColor(Color.BLACK);

            Font titleFont = new Font("Serif", Font.PLAIN, 26);
            Font bodyFont = new Font("Serif", Font.PLAIN, 19);
            Font footerFont = new Font("Serif", Font.PLAIN, 17);

            int left = 36;
            int right = receiptWidth - 36;

            int y = 22;

            // ---- TITLE ----

            g2.setFont(titleFont);

            FontMetrics fm = g2.getFontMetrics();

            drawCentered(
                g2, "Cof/Tea Café Receipt",
                receiptWidth / 2, y + fm.getAscent()
            );

            y += fm.getHeight() + 4;

            // ---- BODY ----

            g2.setFont(bodyFont);

            fm = g2.getFontMetrics();

            int lineH = fm.getHeight() + 4;

            y = drawDashes(g2, left, right, y, lineH);

            // ---- DATE / TIME / ORDER NUMBER ----

            DateTimeFormatter dateFormat =
                DateTimeFormatter.ofPattern("MM/dd/yyyy", Locale.US);

            DateTimeFormatter timeFormat =
                DateTimeFormatter.ofPattern("hh:mm a", Locale.US);

            int valueX =
                left + fm.stringWidth("Order No.:") + 16;

            y = drawLabelValue(
                g2, "Date:",
                transaction.dateTime.format(dateFormat),
                left, valueX, y, lineH
            );

            y = drawLabelValue(
                g2, "Time:",
                transaction.dateTime.format(timeFormat),
                left, valueX, y, lineH
            );

            y = drawLabelValue(
                g2, "Order No.:",
                String.format("%04d", transaction.transactionNumber),
                left, valueX, y, lineH
            );

            y = drawDashes(g2, left, right, y, lineH);

            // ---- ITEMS ----
            // "2 x Original Latte (12 OZ - Espresso)      ₱98.00"

            int priceWidth = 0;

            for (OrderLine line : transaction.items) {

                priceWidth = Math.max(
                    priceWidth,
                    fm.stringWidth(peso(line.getLineTotal()))
                );
            }

            int textMaxWidth = (right - left) - priceWidth - 16;

            for (OrderLine line : transaction.items) {

                String prefix = line.quantity + " x ";

                int prefixWidth = fm.stringWidth(prefix);

                List<String> parts = wrapText(
                    line.getDescription(),
                    fm,
                    textMaxWidth - prefixWidth
                );

                for (int i = 0; i < parts.size(); i++) {

                    int baseline = y + fm.getAscent();

                    if (i == 0) {

                        g2.drawString(
                            prefix + parts.get(0), left, baseline
                        );

                        drawRight(
                            g2, peso(line.getLineTotal()),
                            right, baseline
                        );

                    } else {

                        // Wrapped lines line up under the item name
                        g2.drawString(
                            parts.get(i), left + prefixWidth, baseline
                        );
                    }

                    y += lineH;
                }
            }

            y = drawDashes(g2, left, right, y, lineH);

            // ---- TOTALS ----

            y = drawRow(
                g2, "Subtotal:", peso(transaction.subtotal),
                left, right, y, lineH
            );

            y = drawRow(
                g2, "12% VAT:", peso(transaction.tax),
                left, right, y, lineH
            );

            // Senior/PWD line only appears when a discount was applied
            if (transaction.discount.signum() > 0) {

                y = drawRow(
                    g2, "Senior/PWD Discount:",
                    "-" + peso(transaction.discount),
                    left, right, y, lineH
                );
            }

            y = drawRow(
                g2, "Total amount:", peso(transaction.total),
                left, right, y, lineH
            );

            y = drawRow(
                g2, "Paid:", peso(transaction.amountReceived),
                left, right, y, lineH
            );

            y = drawRow(
                g2, "Change:", peso(transaction.change),
                left, right, y, lineH
            );

            y = drawDashes(g2, left, right, y, lineH);

            // ---- PAYMENT METHOD ----

            String method = transaction.paymentMethod;

            String methodText = method.substring(0, 1)
                + method.substring(1).toLowerCase();

            y = drawRow(
                g2, "Payment method:", methodText,
                left, right, y, lineH
            );

            // Blank space before the footer line, like the reference
            y += lineH / 2;

            y = drawDashes(g2, left, right, y, lineH);

            // ---- FOOTER ----

            g2.setFont(footerFont);

            fm = g2.getFontMetrics();

            int footerH = fm.getHeight() + 6;

            drawCentered(
                g2, "Thank You! Come",
                receiptWidth / 2, y + fm.getAscent()
            );

            y += footerH;

            drawCentered(
                g2, "Again Soon!",
                receiptWidth / 2, y + fm.getAscent()
            );

            y += footerH;

            // Bottom padding
            return y + 14;
        }

        // ---- small drawing helpers ----

        private void drawCentered(
            Graphics2D g2, String text, int centerX, int baseline
        ) {
            int width = g2.getFontMetrics().stringWidth(text);

            g2.drawString(text, centerX - width / 2, baseline);
        }

        private void drawRight(
            Graphics2D g2, String text, int rightX, int baseline
        ) {
            int width = g2.getFontMetrics().stringWidth(text);

            g2.drawString(text, rightX - width, baseline);
        }

        // Line of dashes the width of the receipt, like the reference
        private int drawDashes(
            Graphics2D g2, int left, int right, int y, int lineH
        ) {
            FontMetrics fm = g2.getFontMetrics();

            // Fractional width, so the line never runs past the margin
            double dashWidth =
                fm.getStringBounds("-", g2).getWidth();

            int count = (int) ((right - left) / dashWidth);

            StringBuilder dashes = new StringBuilder();

            for (int i = 0; i < count; i++) {
                dashes.append('-');
            }

            g2.drawString(
                dashes.toString(), left, y + fm.getAscent()
            );

            return y + lineH;
        }

        // "Date:   01/30/2000"
        private int drawLabelValue(
            Graphics2D g2,
            String label,
            String value,
            int left,
            int valueX,
            int y,
            int lineH
        ) {
            int baseline = y + g2.getFontMetrics().getAscent();

            g2.drawString(label, left, baseline);
            g2.drawString(value, valueX, baseline);

            return y + lineH;
        }

        // "Subtotal:                    ₱87.50"
        private int drawRow(
            Graphics2D g2,
            String label,
            String value,
            int left,
            int right,
            int y,
            int lineH
        ) {
            int baseline = y + g2.getFontMetrics().getAscent();

            g2.drawString(label, left, baseline);

            drawRight(g2, value, right, baseline);

            return y + lineH;
        }
    }

    // Splits text into lines that fit maxWidth (breaks at spaces)
    private static List<String> wrapText(
        String text,
        FontMetrics fm,
        int maxWidth
    ) {

        List<String> lines = new ArrayList<>();

        StringBuilder current = new StringBuilder();

        for (String word : text.split(" ")) {

            // A single word wider than the line is broken up
            while (fm.stringWidth(word) > maxWidth
                && word.length() > 1) {

                int cut = word.length() - 1;

                while (cut > 1 && fm.stringWidth(
                    word.substring(0, cut)) > maxWidth) {
                    cut--;
                }

                if (current.length() > 0) {
                    lines.add(current.toString());
                    current.setLength(0);
                }

                lines.add(word.substring(0, cut));

                word = word.substring(cut);
            }

            String candidate = current.length() == 0
                ? word
                : current + " " + word;

            if (current.length() == 0
                || fm.stringWidth(candidate) <= maxWidth) {

                current.setLength(0);
                current.append(candidate);

            } else {

                lines.add(current.toString());

                current.setLength(0);
                current.append(word);
            }
        }

        if (current.length() > 0) {
            lines.add(current.toString());
        }

        if (lines.isEmpty()) {
            lines.add("");
        }

        return lines;
    }

    // ==========================================
    // 13D. AUTOMATIC TEXT FILE RECEIPT
    // ==========================================

    // Folder where every receipt .txt file is saved
    private static final Path RECEIPT_FOLDER = Paths.get(
        "C:\\Users\\gonza\\OneDrive\\Desktop\\COFTEA_POS\\receipt"
    );

    // Characters per line in the text receipt
    private static final int TEXT_RECEIPT_WIDTH = 42;

    // Saves the receipt file and shows an error if it fails.
    // The payment itself is already completed at this point.
    private void saveReceiptFileAutomatically(
        TransactionRecord record
    ) {

        try {

            saveReceiptToFile(record);

        } catch (IOException | SecurityException ex) {

            JOptionPane.showMessageDialog(
                dashboardFrame,
                "The payment was completed, but the receipt "
                    + "file could NOT be saved.\n\n"
                    + "Folder: " + RECEIPT_FOLDER + "\n"
                    + "Reason: " + ex.getMessage(),
                "Receipt File Error",
                JOptionPane.ERROR_MESSAGE
            );
        }
    }

    // Writes one NEW file per transaction. Existing files are never
    // overwritten: CREATE_NEW fails if the name is already taken,
    // and the next free name is used instead.
    private Path saveReceiptToFile(TransactionRecord t)
        throws IOException {

        // Creates the folder (and parents) only if it is missing
        Files.createDirectories(RECEIPT_FOLDER);

        String stamp = t.dateTime.format(
            DateTimeFormatter.ofPattern(
                "yyyy-MM-dd_HH-mm-ss", Locale.US
            )
        );

        String baseName = String.format(
            Locale.US, "Receipt_%s_%03d",
            stamp, t.transactionNumber
        );

        byte[] data = buildReceiptText(t)
            .getBytes(StandardCharsets.UTF_8);

        for (int attempt = 1; attempt <= 1000; attempt++) {

            String fileName = attempt == 1
                ? baseName + ".txt"
                : baseName + "_" + attempt + ".txt";

            Path file = RECEIPT_FOLDER.resolve(fileName);

            try {

                Files.write(
                    file, data,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
                );

                return file;

            } catch (FileAlreadyExistsException ex) {
                // Name already used: try the next suffix
            }
        }

        throw new IOException(
            "Could not find an unused file name for the receipt."
        );
    }

    // Builds the same content as the receipt window, as plain text
    private static String buildReceiptText(TransactionRecord t) {

        final int w = TEXT_RECEIPT_WIDTH;

        String nl = System.lineSeparator();
        String dashes = repeatChar('-', w);

        StringBuilder sb = new StringBuilder();

        sb.append(centerText("Cof/Tea Café Receipt", w)).append(nl);
        sb.append(dashes).append(nl);

        sb.append(String.format("%-12s%s", "Date:",
            t.dateTime.format(DateTimeFormatter.ofPattern(
                "MM/dd/yyyy", Locale.US)))).append(nl);

        sb.append(String.format("%-12s%s", "Time:",
            t.dateTime.format(DateTimeFormatter.ofPattern(
                "hh:mm a", Locale.US)))).append(nl);

        sb.append(String.format("%-12s%04d", "Order No.:",
            t.transactionNumber)).append(nl);

        sb.append(dashes).append(nl);

        // ---- ITEMS ----

        int priceWidth = 0;

        for (OrderLine line : t.items) {
            priceWidth = Math.max(
                priceWidth, peso(line.getLineTotal()).length()
            );
        }

        for (OrderLine line : t.items) {

            String prefix = line.quantity + " x ";
            String indent = repeatChar(' ', prefix.length());

            List<String> parts = wrapPlain(
                line.getDescription(),
                w - priceWidth - 2 - prefix.length()
            );

            for (int i = 0; i < parts.size(); i++) {

                if (i == 0) {
                    sb.append(textRow(
                        prefix + parts.get(0),
                        peso(line.getLineTotal()), w
                    ));
                } else {
                    sb.append(indent).append(parts.get(i));
                }

                sb.append(nl);
            }

            // Individual price, shown when more than one cup
            if (line.quantity > 1) {
                sb.append(indent).append("@ ")
                    .append(peso(line.getUnitPrice()))
                    .append(" each").append(nl);
            }
        }

        sb.append(dashes).append(nl);

        // ---- TOTALS ----

        sb.append(textRow("Subtotal:", peso(t.subtotal), w)).append(nl);
        sb.append(textRow("12% VAT:", peso(t.tax), w)).append(nl);

        // Senior/PWD line only appears when a discount was applied
        if (t.discount.signum() > 0) {
            sb.append(textRow(
                "Senior/PWD Discount:",
                "-" + peso(t.discount), w
            )).append(nl);
        }

        sb.append(textRow("Total amount:", peso(t.total), w)).append(nl);
        sb.append(textRow("Paid:", peso(t.amountReceived), w)).append(nl);
        sb.append(textRow("Change:", peso(t.change), w)).append(nl);

        sb.append(dashes).append(nl);

        // ---- PAYMENT METHOD ----

        String method = t.paymentMethod.substring(0, 1)
            + t.paymentMethod.substring(1).toLowerCase();

        sb.append(textRow("Payment method:", method, w)).append(nl);

        sb.append(nl);
        sb.append(dashes).append(nl);

        // ---- FOOTER ----

        sb.append(centerText("Thank You! Come", w)).append(nl);
        sb.append(centerText("Again Soon!", w)).append(nl);

        return sb.toString();
    }

    // "label ........ value" padded to the receipt width
    private static String textRow(
        String left, String right, int width
    ) {
        int spaces = Math.max(1, width - left.length() - right.length());

        return left + repeatChar(' ', spaces) + right;
    }

    private static String centerText(String text, int width) {

        int pad = Math.max(0, (width - text.length()) / 2);

        return repeatChar(' ', pad) + text;
    }

    private static String repeatChar(char c, int count) {

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < count; i++) {
            sb.append(c);
        }

        return sb.toString();
    }

    // Plain-text word wrap (the receipt window uses pixel widths instead)
    private static List<String> wrapPlain(String text, int maxWidth) {

        List<String> lines = new ArrayList<>();

        StringBuilder current = new StringBuilder();

        for (String word : text.split(" ")) {

            // A single word longer than the line is broken up
            while (word.length() > maxWidth) {

                if (current.length() > 0) {
                    lines.add(current.toString());
                    current.setLength(0);
                }

                lines.add(word.substring(0, maxWidth));
                word = word.substring(maxWidth);
            }

            if (current.length() == 0) {

                current.append(word);

            } else if (current.length() + 1 + word.length() <= maxWidth) {

                current.append(' ').append(word);

            } else {

                lines.add(current.toString());
                current.setLength(0);
                current.append(word);
            }
        }

        if (current.length() > 0) {
            lines.add(current.toString());
        }

        if (lines.isEmpty()) {
            lines.add("");
        }

        return lines;
    }

    // ==========================================
    // 14. CATEGORY BUTTON DESIGN
    // ==========================================

    private JButton createCategoryButton(
        String text
    ) {

        JButton button = new JButton(text);

        button.setFont(
            new Font("Arial", Font.BOLD, 25)
        );

        button.setForeground(white);
        button.setBackground(blue);

        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setOpaque(true);

        button.setCursor(
            new Cursor(Cursor.HAND_CURSOR)
        );

        return button;
    }

    // ==========================================
    // 15. DASHBOARD BUTTON DESIGN
    // ==========================================

    private JButton createDashboardButton(
        String text,
        Color backgroundColor
    ) {

        JButton button = new JButton(text);

        button.setFont(
            new Font("Arial", Font.BOLD, 23)
        );

        button.setForeground(white);
        button.setBackground(backgroundColor);

        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setOpaque(true);

        button.setCursor(
            new Cursor(Cursor.HAND_CURSOR)
        );

        return button;
    }

    // ==========================================
    // 16. CIRCULAR SIZE-SELECTION BUTTON
    // ==========================================

    private class CircleSelectionButton extends JButton {

        private boolean selected = false;

        public CircleSelectionButton() {

            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);

            setToolTipText("Select size");
        }

        public boolean isSelected() {
            return selected;
        }

        public void setSelected(boolean selected) {

            this.selected = selected;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {

            Graphics2D g2 =
                (Graphics2D) g.create();

            g2.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
            );

            int diameter =
                Math.min(getWidth(), getHeight()) - 8;

            int x = (getWidth() - diameter) / 2;
            int y = (getHeight() - diameter) / 2;

            // Circle background

            g2.setColor(selected ? green : white);

            g2.fillOval(
                x, y, diameter, diameter
            );

            // Circle outline

            g2.setColor(Color.BLACK);

            g2.setStroke(
                new BasicStroke(2.0f)
            );

            g2.drawOval(
                x, y, diameter, diameter
            );

            // Selected checkmark

            if (selected) {

                g2.setColor(white);

                g2.setStroke(
                    new BasicStroke(
                        4.0f,
                        BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND
                    )
                );

                int cx = getWidth() / 2;
                int cy = getHeight() / 2;

                g2.drawLine(
                    cx - 10, cy,
                    cx - 3, cy + 8
                );

                g2.drawLine(
                    cx - 3, cy + 8,
                    cx + 12, cy - 9
                );
            }

            g2.dispose();
        }
    }

    // ==========================================
    // 17. LOGO DESIGN
    // ==========================================

    private class LogoPanel extends JPanel {

        public LogoPanel() {

            setPreferredSize(
                new Dimension(100, 100)
            );

            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {

            super.paintComponent(g);

            Graphics2D g2 =
                (Graphics2D) g.create();

            g2.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
            );

            // CIRCULAR BACKGROUND

            g2.setColor(
                new Color(241, 236, 229)
            );

            g2.fillOval(0, 0, 100, 100);

            // LOGO TEXT

            g2.setFont(
                new Font("Serif", Font.PLAIN, 25)
            );

            g2.setColor(
                new Color(220, 75, 35)
            );

            g2.drawString("cof", 10, 47);

            g2.setColor(
                new Color(65, 135, 80)
            );

            g2.drawString("/", 57, 40);

            g2.setColor(
                new Color(35, 100, 160)
            );

            g2.drawString("tea", 53, 64);

            // CAFE TEXT

            g2.setFont(
                new Font("Arial", Font.PLAIN, 7)
            );

            g2.setColor(
                new Color(180, 175, 80)
            );

            g2.drawString(
                "C A F E", 34, 76
            );

            g2.dispose();
        }
    }

    // ==========================================
    // 18. ADD-ON WINDOW COMPONENTS
    // ==========================================

    // Panel with rounded corners, optional rounded border
    private class RoundedPanel extends JPanel {

        private final Color fill;
        private final Color borderColor;
        private final int stroke;
        private final int radius;

        RoundedPanel(
            Color fill,
            Color borderColor,
            int stroke,
            int radius
        ) {
            this.fill = fill;
            this.borderColor = borderColor;
            this.stroke = stroke;
            this.radius = radius;

            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {

            Graphics2D g2 = (Graphics2D) g.create();

            g2.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
            );

            int inset = stroke / 2;

            g2.setColor(fill);

            g2.fillRoundRect(
                inset, inset,
                getWidth() - stroke - 1,
                getHeight() - stroke - 1,
                radius, radius
            );

            if (borderColor != null && stroke > 0) {

                g2.setColor(borderColor);
                g2.setStroke(new BasicStroke(stroke));

                g2.drawRoundRect(
                    inset, inset,
                    getWidth() - stroke - 1,
                    getHeight() - stroke - 1,
                    radius, radius
                );
            }

            g2.dispose();

            super.paintComponent(g);
        }
    }

    // Rounded pill toggle used for each add-on
    private class PillToggle extends JToggleButton {

        PillToggle() {

            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);

            setPreferredSize(new Dimension(90, 48));

            setCursor(new Cursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics g) {

            Graphics2D g2 = (Graphics2D) g.create();

            g2.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
            );

            g2.setColor(isSelected() ? green : pillGray);

            g2.fillRoundRect(
                0, 0, getWidth(), getHeight(),
                getHeight(), getHeight()
            );

            if (isSelected()) {

                g2.setColor(white);

                g2.setStroke(
                    new BasicStroke(
                        4.0f,
                        BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND
                    )
                );

                int cx = getWidth() / 2;
                int cy = getHeight() / 2;

                g2.drawLine(cx - 10, cy, cx - 3, cy + 8);
                g2.drawLine(cx - 3, cy + 8, cx + 12, cy - 9);
            }

            g2.dispose();
        }
    }

    // Rounded ADD / CANCEL buttons
    private class RoundedButton extends JButton {

        private final Color buttonColor;

        RoundedButton(String text, Color buttonColor) {

            super(text);

            this.buttonColor = buttonColor;

            setFont(new Font("Arial", Font.BOLD, 28));
            setForeground(white);

            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);

            setCursor(new Cursor(Cursor.HAND_CURSOR));
        }

        @Override
        protected void paintComponent(Graphics g) {

            Graphics2D g2 = (Graphics2D) g.create();

            g2.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING,
                RenderingHints.VALUE_ANTIALIAS_ON
            );

            g2.setColor(buttonColor);

            g2.fillRoundRect(
                0, 0, getWidth(), getHeight(), 28, 28
            );

            g2.dispose();

            super.paintComponent(g);
        }
    }
}
