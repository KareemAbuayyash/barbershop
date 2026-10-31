package com.barbershop.backend.account;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "barber.admin")
public class OwnerBootstrapProperties {

	private String username = "";
	private String password = "";
	private boolean bootstrapEnabled = true;

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username == null ? "" : username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password == null ? "" : password;
	}

	public boolean isBootstrapEnabled() {
		return bootstrapEnabled;
	}

	public void setBootstrapEnabled(boolean bootstrapEnabled) {
		this.bootstrapEnabled = bootstrapEnabled;
	}
}
