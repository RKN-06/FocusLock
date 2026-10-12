from functools import lru_cache
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")
    database_url: str = ""
    app_env: str = "production"
    enable_dev_auth: bool = False
    dev_password: str = ""
    qr_signing_key: str = ""
    policy_signing_key: str = ""
    resource_allowed_hosts: str = ""
    ble_token_key: str = ""
    dev_receiver_token: str = ""
    oidc_issuer: str = ""
    oidc_audience: str = ""
    oidc_jwks_url: str = ""
    oidc_authorization_endpoint: str = ""
    oidc_token_endpoint: str = ""
    oidc_dashboard_client_id: str = ""
    oidc_dashboard_redirect_uri: str = ""
    oidc_android_client_id: str = ""
    oidc_android_redirect_uri: str = ""
    play_integrity_mode: str = "off"
    play_integrity_package: str = ""
    play_integrity_service_account_file: str = ""
    play_integrity_service_account_json: str = ""
    cron_secret: str = ""
    retention_event_days: int = 30
    retention_sighting_days: int = 7
    retention_credential_days: int = 1
    cors_origins: str = "http://localhost:5173"
    # Time slots are wall-clock times in this IANA zone, so a 09:00 slot is 09:00 at the college wherever the teacher's computer is.
    campus_timezone: str = "Asia/Kolkata"
    face_template_key: str = ""
    # Local compatibility default; production configuration must set this false.
    face_template_client_download: bool = True
    # Production-safe default: biometric checks must be explicitly enabled by deployment configuration.
    face_check_mode: str = "enrolled"  # off | enrolled | required; production example sets off

    @property
    def dev_auth_enabled(self) -> bool:
        return self.app_env == "development" and self.enable_dev_auth and bool(self.dev_password)

    @property
    def oidc_configured(self) -> bool:
        return bool(self.oidc_issuer.startswith("https://") and self.oidc_audience and self.oidc_jwks_url.startswith("https://"))

    @property
    def oidc_clients_configured(self) -> bool:
        return bool(self.oidc_configured and self.oidc_authorization_endpoint.startswith("https://") and
            self.oidc_token_endpoint.startswith("https://") and self.oidc_dashboard_client_id and
            self.oidc_dashboard_redirect_uri.startswith("https://") and self.oidc_android_client_id and
            self.oidc_android_redirect_uri == "focuslock://oidc/callback")


@lru_cache
def settings() -> Settings:
    return Settings()
