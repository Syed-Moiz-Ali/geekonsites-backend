package com.geekonsites.backend.service;

import com.geekonsites.backend.entity.Service;
import com.geekonsites.backend.entity.ServiceAddon;
import com.geekonsites.backend.entity.ServicePrice;
import com.geekonsites.backend.enums.Currency;
import com.geekonsites.backend.enums.ServiceMode;
import com.geekonsites.backend.repository.ServiceAddonRepository;
import com.geekonsites.backend.repository.ServicePriceRepository;
import com.geekonsites.backend.repository.ServiceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

/**
 * PHASE 6 — idempotent seed of the pre-existing hardcoded catalog into the database.
 *
 * <p>Create-if-absent by stable code; existing rows (and their Admin-edited prices) are left
 * untouched, so repeated startups never duplicate data or overwrite admin changes. The source
 * data mirrors the former {@code TrustedPricingService} maps exactly (US/GBP amounts, modes).
 *
 * <p>Controlled by {@code app.catalog.seed} (default true). This is the current-project
 * migration/seed mechanism; it must be folded into the future versioned Flyway baseline.
 */
@Component
public class ServiceCatalogSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ServiceCatalogSeeder.class);

    private final ServiceRepository serviceRepository;
    private final ServicePriceRepository servicePriceRepository;
    private final ServiceAddonRepository serviceAddonRepository;

    @Value("${app.catalog.seed:true}")
    private boolean enabled;

    public ServiceCatalogSeeder(
            ServiceRepository serviceRepository,
            ServicePriceRepository servicePriceRepository,
            ServiceAddonRepository serviceAddonRepository
    ) {
        this.serviceRepository = serviceRepository;
        this.servicePriceRepository = servicePriceRepository;
        this.serviceAddonRepository = serviceAddonRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        seedServices();
        seedAddons();
    }

    private void seedServices() {
        int order = 0;
        order = seedService(order, "PC Health Check & Diagnosis", 29, 25, ServiceMode.REMOTE);
        order = seedService(order, "Virus & Malware Removal", 79, 69, ServiceMode.REMOTE);
        order = seedService(order, "Slow PC Optimization", 59, 49, ServiceMode.REMOTE);
        order = seedService(order, "Windows Troubleshooting", 69, 59, ServiceMode.REMOTE);
        order = seedService(order, "Printer Setup & Configuration", 49, 39, ServiceMode.REMOTE);
        order = seedService(order, "Email Setup & Fixes", 39, 35, ServiceMode.REMOTE);
        order = seedService(order, "Software Installation", 39, 35, ServiceMode.REMOTE);
        order = seedService(order, "Microsoft Office Setup", 49, 39, ServiceMode.REMOTE);
        order = seedService(order, "Driver Installation", 39, 35, ServiceMode.REMOTE);
        order = seedService(order, "Wi-Fi & Network Troubleshooting", 69, 59, ServiceMode.REMOTE);
        order = seedService(order, "Password Recovery Assistance", 49, 39, ServiceMode.REMOTE);
        order = seedService(order, "Data Backup Configuration", 59, 49, ServiceMode.REMOTE);
        order = seedService(order, "New PC Setup (Remote)", 99, 89, ServiceMode.REMOTE);
        order = seedService(order, "Laptop Repair", 129, 109, ServiceMode.ONSITE);
        order = seedService(order, "Remote IT Support", 99.99, 79.99, ServiceMode.REMOTE);
        order = seedService(order, "Computer Repair", 99.99, 79.99, ServiceMode.ONSITE);
        order = seedService(order, "Desktop Repair", 139, 119, ServiceMode.ONSITE);
        order = seedService(order, "CCTV Installation", 199, 179, ServiceMode.ONSITE);
        order = seedService(order, "Router Setup", 99, 89, ServiceMode.ONSITE);
        order = seedService(order, "Smart Home Setup", 109, 95, ServiceMode.ONSITE);
        order = seedService(order, "Business IT Support", 149, 129, ServiceMode.ONSITE);
        order = seedService(order, "Managed IT Services", 199, 179, ServiceMode.ONSITE);
        order = seedService(order, "Cloud Support", 129, 109, ServiceMode.ONSITE);
        order = seedService(order, "Server Setup", 179, 159, ServiceMode.ONSITE);
        order = seedService(order, "Office Networking", 149, 129, ServiceMode.ONSITE);
        order = seedService(order, "Business Security", 129, 109, ServiceMode.ONSITE);
        order = seedService(order, "PC Protection Bundle", 99, 85, ServiceMode.ONSITE);
        order = seedService(order, "New Computer Setup", 149, 129, ServiceMode.ONSITE);
        order = seedService(order, "Work-from-Home Bundle", 199, 169, ServiceMode.ONSITE);
        order = seedService(order, "Networking Product Recommendation", 19, 15, ServiceMode.REMOTE);
        order = seedService(order, "Printer & Office Product Recommendation", 19, 15, ServiceMode.REMOTE);
        order = seedService(order, "Storage Product Recommendation", 19, 15, ServiceMode.REMOTE);
        order = seedService(order, "Accessories Product Recommendation", 19, 15, ServiceMode.REMOTE);
        seedService(order, "Antivirus Product Recommendation", 19, 15, ServiceMode.REMOTE);
    }

    private int seedService(int sortOrder, String name, double usd, double gbp, ServiceMode mode) {
        String code = codeFromName(name);
        if (serviceRepository.findByCodeIgnoreCase(code).isPresent()) {
            return sortOrder + 1;
        }
        Service service = new Service();
        service.setCode(code);
        service.setName(name);
        service.setServiceMode(mode);
        service.setSortOrder(sortOrder);
        service.setActive(true);
        service = serviceRepository.save(service);
        savePrice(service.getId(), Currency.USD, Math.round(usd * 100.0));
        savePrice(service.getId(), Currency.GBP, Math.round(gbp * 100.0));
        log.info("Seeded service {} ({})", code, name);
        return sortOrder + 1;
    }

    private void seedAddons() {
        int order = 0;
        order = seedAddon(order, "priority-support", 19, 15);
        order = seedAddon(order, "extended-remote", 29, 25);
        order = seedAddon(order, "pc-optimization", 39, 35);
        order = seedAddon(order, "virus-cleanup", 49, 39);
        order = seedAddon(order, "data-backup", 39, 35);
        order = seedAddon(order, "email-setup", 29, 25);
        order = seedAddon(order, "office-setup", 39, 35);
        order = seedAddon(order, "cloud-sync", 49, 39);
        order = seedAddon(order, "password-manager", 29, 25);
        order = seedAddon(order, "remote-training", 25, 20);
        order = seedAddon(order, "mouse", 19, 15);
        order = seedAddon(order, "keyboard", 29, 25);
        order = seedAddon(order, "laptop-charger", 49, 39);
        order = seedAddon(order, "usb-c-charger", 59, 49);
        order = seedAddon(order, "laptop-bag", 39, 29);
        order = seedAddon(order, "cooling-pad", 24, 19);
        order = seedAddon(order, "external-hdd", 79, 69);
        order = seedAddon(order, "ssd-upgrade", 79, 69);
        order = seedAddon(order, "ram-upgrade", 59, 49);
        order = seedAddon(order, "screen-protector", 19, 15);
        order = seedAddon(order, "premium-cleaning", 29, 25);
        order = seedAddon(order, "monitor-cable", 19, 15);
        order = seedAddon(order, "thermal-paste", 29, 25);
        order = seedAddon(order, "dust-cleaning", 39, 35);
        order = seedAddon(order, "wifi-adapter", 29, 25);
        order = seedAddon(order, "bluetooth-adapter", 19, 15);
        order = seedAddon(order, "backup-drive", 49, 39);
        order = seedAddon(order, "wireless-printer", 29, 25);
        order = seedAddon(order, "ink", 39, 35);
        order = seedAddon(order, "scanner", 25, 20);
        order = seedAddon(order, "printer-cable", 15, 12);
        order = seedAddon(order, "paper-pack", 15, 12);
        order = seedAddon(order, "extended-printer", 29, 25);
        order = seedAddon(order, "network-printer", 49, 39);
        order = seedAddon(order, "driver-install", 25, 20);
        order = seedAddon(order, "mobile-print", 29, 25);
        order = seedAddon(order, "wifi-extender", 69, 59);
        order = seedAddon(order, "mesh-setup", 99, 89);
        order = seedAddon(order, "router-config", 49, 39);
        order = seedAddon(order, "network-security", 39, 35);
        order = seedAddon(order, "guest-network", 29, 25);
        order = seedAddon(order, "parental-control", 29, 25);
        order = seedAddon(order, "office-network", 79, 69);
        order = seedAddon(order, "ethernet-cable", 19, 15);
        order = seedAddon(order, "smart-home-wifi", 49, 39);
        order = seedAddon(order, "extra-camera", 89, 79);
        order = seedAddon(order, "cloud-recording", 39, 35);
        order = seedAddon(order, "mobile-monitoring", 29, 25);
        order = seedAddon(order, "dvr-setup", 49, 39);
        order = seedAddon(order, "camera-maintenance", 49, 39);
        order = seedAddon(order, "night-vision-check", 29, 25);
        order = seedAddon(order, "motion-alerts", 29, 25);
        order = seedAddon(order, "storage-drive", 79, 69);
        order = seedAddon(order, "remote-viewing", 39, 35);
        order = seedAddon(order, "business-priority", 99, 89);
        order = seedAddon(order, "cloud-backup", 79, 69);
        order = seedAddon(order, "managed-it", 149, 129);
        order = seedAddon(order, "security-audit", 99, 89);
        order = seedAddon(order, "email-business", 79, 69);
        order = seedAddon(order, "server-check", 149, 129);
        order = seedAddon(order, "device-onboarding", 49, 39);
        order = seedAddon(order, "monthly-maintenance", 199, 179);
        order = seedAddon(order, "replacement-screen", 129, 109);
        order = seedAddon(order, "external-monitor", 49, 39);
        order = seedAddon(order, "battery-replacement", 89, 79);
        order = seedAddon(order, "fast-charger", 59, 49);
        order = seedAddon(order, "gos-secure", 29, 24);
        seedAddon(order, "printer-driver", 25, 20);
    }

    private int seedAddon(int sortOrder, String code, double usd, double gbp) {
        if (serviceAddonRepository.existsByCode(code)) {
            return sortOrder + 1;
        }
        ServiceAddon addon = new ServiceAddon();
        addon.setCode(code);
        addon.setName(code);
        addon.setUsdAmountMinor(Math.round(usd * 100.0));
        addon.setGbpAmountMinor(Math.round(gbp * 100.0));
        addon.setSortOrder(sortOrder);
        addon.setActive(true);
        serviceAddonRepository.save(addon);
        return sortOrder + 1;
    }

    private void savePrice(Long serviceId, Currency currency, long amountMinor) {
        ServicePrice price = new ServicePrice();
        price.setServiceId(serviceId);
        price.setCurrency(currency);
        price.setAmountMinor(amountMinor);
        servicePriceRepository.save(price);
    }

    static String codeFromName(String name) {
        return name.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }
}
