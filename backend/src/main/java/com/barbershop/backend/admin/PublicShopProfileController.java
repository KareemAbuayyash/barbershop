package com.barbershop.backend.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.barbershop.backend.admin.AdminDtos.ShopProfile;

@RestController
@RequestMapping("/api/shop-profile")
public class PublicShopProfileController {

	private final AdminManagementService adminManagementService;

	public PublicShopProfileController(AdminManagementService adminManagementService) {
		this.adminManagementService = adminManagementService;
	}

	@GetMapping
	public ShopProfile getShopProfile() {
		return adminManagementService.shopProfile();
	}
}
