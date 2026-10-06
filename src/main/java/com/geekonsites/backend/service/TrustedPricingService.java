package com.geekonsites.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.geekonsites.backend.dto.BookingRequest;
import com.geekonsites.backend.enums.ServiceMode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

import java.util.Map;
import static java.util.Map.entry;

@Service
public class TrustedPricingService {
    private record ServicePrice(double us, double uk, ServiceMode mode) {}
    private record AddonPrice(double us, double uk) {}

    private static final Map<String, ServicePrice> SERVICES = Map.ofEntries(
        service("PC Health Check & Diagnosis",29,25,ServiceMode.REMOTE), service("Virus & Malware Removal",79,69,ServiceMode.REMOTE),
        service("Slow PC Optimization",59,49,ServiceMode.REMOTE), service("Windows Troubleshooting",69,59,ServiceMode.REMOTE),
        service("Printer Setup & Configuration",49,39,ServiceMode.REMOTE), service("Email Setup & Fixes",39,35,ServiceMode.REMOTE),
        service("Software Installation",39,35,ServiceMode.REMOTE), service("Microsoft Office Setup",49,39,ServiceMode.REMOTE),
        service("Driver Installation",39,35,ServiceMode.REMOTE), service("Wi-Fi & Network Troubleshooting",69,59,ServiceMode.REMOTE),
        service("Password Recovery Assistance",49,39,ServiceMode.REMOTE), service("Data Backup Configuration",59,49,ServiceMode.REMOTE),
        service("New PC Setup (Remote)",99,89,ServiceMode.REMOTE), service("Laptop Repair",129,109,ServiceMode.ONSITE),
        service("Remote IT Support",99.99,79.99,ServiceMode.REMOTE), service("Computer Repair",99.99,79.99,ServiceMode.ONSITE),
        service("Desktop Repair",139,119,ServiceMode.ONSITE), service("CCTV Installation",199,179,ServiceMode.ONSITE),
        service("Router Setup",99,89,ServiceMode.ONSITE), service("Smart Home Setup",109,95,ServiceMode.ONSITE),
        service("Business IT Support",149,129,ServiceMode.ONSITE), service("Managed IT Services",199,179,ServiceMode.ONSITE),
        service("Cloud Support",129,109,ServiceMode.ONSITE), service("Server Setup",179,159,ServiceMode.ONSITE),
        service("Office Networking",149,129,ServiceMode.ONSITE), service("Business Security",129,109,ServiceMode.ONSITE),
        service("PC Protection Bundle",99,85,ServiceMode.ONSITE), service("New Computer Setup",149,129,ServiceMode.ONSITE),
        service("Work-from-Home Bundle",199,169,ServiceMode.ONSITE), service("Networking Product Recommendation",19,15,ServiceMode.REMOTE),
        service("Printer & Office Product Recommendation",19,15,ServiceMode.REMOTE), service("Storage Product Recommendation",19,15,ServiceMode.REMOTE),
        service("Accessories Product Recommendation",19,15,ServiceMode.REMOTE), service("Antivirus Product Recommendation",19,15,ServiceMode.REMOTE)
    );

    private static final Map<String, AddonPrice> ADDONS = Map.ofEntries(
        addon("priority-support",19,15), addon("extended-remote",29,25), addon("pc-optimization",39,35), addon("virus-cleanup",49,39),
        addon("data-backup",39,35), addon("email-setup",29,25), addon("office-setup",39,35), addon("cloud-sync",49,39),
        addon("password-manager",29,25), addon("remote-training",25,20), addon("mouse",19,15), addon("keyboard",29,25),
        addon("laptop-charger",49,39), addon("usb-c-charger",59,49), addon("laptop-bag",39,29), addon("cooling-pad",24,19),
        addon("external-hdd",79,69), addon("ssd-upgrade",79,69), addon("ram-upgrade",59,49), addon("screen-protector",19,15),
        addon("premium-cleaning",29,25), addon("monitor-cable",19,15), addon("thermal-paste",29,25), addon("dust-cleaning",39,35),
        addon("wifi-adapter",29,25), addon("bluetooth-adapter",19,15), addon("backup-drive",49,39), addon("wireless-printer",29,25),
        addon("ink",39,35), addon("scanner",25,20), addon("printer-cable",15,12), addon("paper-pack",15,12),
        addon("extended-printer",29,25), addon("network-printer",49,39), addon("driver-install",25,20), addon("mobile-print",29,25),
        addon("wifi-extender",69,59), addon("mesh-setup",99,89), addon("router-config",49,39), addon("network-security",39,35),
        addon("guest-network",29,25), addon("parental-control",29,25), addon("office-network",79,69), addon("ethernet-cable",19,15),
        addon("smart-home-wifi",49,39), addon("extra-camera",89,79), addon("cloud-recording",39,35), addon("mobile-monitoring",29,25),
        addon("dvr-setup",49,39), addon("camera-maintenance",49,39), addon("night-vision-check",29,25), addon("motion-alerts",29,25),
        addon("storage-drive",79,69), addon("remote-viewing",39,35), addon("business-priority",99,89), addon("cloud-backup",79,69),
        addon("managed-it",149,129), addon("security-audit",99,89), addon("email-business",79,69), addon("server-check",149,129),
        addon("device-onboarding",49,39), addon("monthly-maintenance",199,179), addon("replacement-screen",129,109), addon("external-monitor",49,39),
        addon("battery-replacement",89,79), addon("fast-charger",59,49), addon("gos-secure",29,24), addon("printer-driver",25,20)
    );

    private final ObjectMapper objectMapper;
    public TrustedPricingService(ObjectMapper objectMapper) { this.objectMapper = objectMapper; }

    public BookingRequest calculatePricing(BookingRequest request) {
        String country = normalizeCountry(request.getCountry());
        boolean uk = "UK".equals(country);
        request.setCountry(country);
        request.setCurrency(uk ? "GBP" : "USD");
        double base = serviceTotal(request.getServiceType(), request.getServiceMode(), uk);
        double addons = addonTotal(request.getSelectedAddons(), uk);
        double total = round(base + addons + 12.0);
        boolean onsite = request.getServiceMode() == ServiceMode.ONSITE;
        double advance = onsite ? round(total * 0.30) : 0;

        request.setBaseAmount(round(base));
        request.setAddonsAmount(round(addons));
        request.setProtectionAmount(0.0);
        request.setPlatformFee(12.0);
        request.setTotalAmount(total);
        request.setAdvanceAmount(advance);
        request.setRemainingAmount(onsite ? round(total - advance) : 0);
        request.setPaidAmount(0.0);
        request.setPaymentType(onsite ? "ADVANCE_PAYMENT" : "FULL_PAYMENT");
        request.setPaymentStatus("PENDING");
        return request;
    }

    private double serviceTotal(String serviceType, ServiceMode mode, boolean uk) {
        if (serviceType == null || serviceType.isBlank() || mode == null) throw badRequest("A valid service and support method are required.");
        double total = 0;
        for (String raw : serviceType.split(",")) {
            String name = raw.trim();
            ServicePrice price = SERVICES.get(name);
            if (price == null) throw badRequest("Selected service is not available.");
            if (price.mode() != mode) throw badRequest("Selected service is not available for the chosen support method.");
            total += uk ? price.uk() : price.us();
        }
        return total;
    }

    private double addonTotal(String selected, boolean uk) {
        if (selected == null || selected.isBlank()) return 0;
        try {
            JsonNode root = objectMapper.readTree(selected);
            if (!root.isArray()) throw badRequest("Invalid add-on selection.");
            double total = 0;
            for (JsonNode item : root) {
                AddonPrice price = ADDONS.get(item.path("id").asText(""));
                int quantity = item.path("quantity").asInt(0);
                if (price == null || quantity < 1 || quantity > 10) throw badRequest("Invalid add-on selection.");
                total += (uk ? price.uk() : price.us()) * quantity;
            }
            return total;
        } catch (RuntimeException exception) { throw exception; }
        catch (Exception exception) { throw badRequest("Invalid add-on selection."); }
    }

    private String normalizeCountry(String country) {
        String value = country == null ? "" : country.trim().toUpperCase().replace('_', ' ');
        if (value.equals("UK") || value.equals("GB") || value.equals("GBR") || value.equals("GBP") || value.equals("UNITED KINGDOM") || value.equals("GREAT BRITAIN")) return "UK";
        if (value.equals("US") || value.equals("USA") || value.equals("USD") || value.equals("UNITED STATES") || value.equals("UNITED STATES OF AMERICA")) return "US";
        throw badRequest("GeekOnSites currently supports United States and United Kingdom bookings only.");
    }

    private double round(double value) { return Math.round(value * 100.0) / 100.0; }
    private ResponseStatusException badRequest(String message) { return new ResponseStatusException(BAD_REQUEST, message); }
    private static Map.Entry<String, ServicePrice> service(String name,double us,double uk,ServiceMode mode) { return entry(name,new ServicePrice(us,uk,mode)); }
    private static Map.Entry<String, AddonPrice> addon(String id,double us,double uk) { return entry(id,new AddonPrice(us,uk)); }
}
