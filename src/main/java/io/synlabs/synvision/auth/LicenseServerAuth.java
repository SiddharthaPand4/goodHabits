package io.synlabs.synvision.auth;

public final class LicenseServerAuth
{
  private LicenseServerAuth()
  {
    throw new AssertionError("Not allowed");
  }

  public static final class Privileges
  {
    public static final String SELF_READ  = "ROLE_SELF_READ";
    public static final String SELF_WRITE = "ROLE_SELF_WRITE";

    public static final String USER_READ  = "ROLE_USER_READ";
    public static final String USER_WRITE = "ROLE_USER_WRITE";

    public static final String INCIDENT_READ = "ROLE_INCIDENT_READ";
    public static final String INCIDENT_WRITE = "ROLE_INCIDENT_WRITE";

    public static final String DEVICE_READ = "ROLE_DEVICE_READ";
    public static final String DEVICE_WRITE = "ROLE_DEVICE_WRITE";
    public static final String TRIGGER_READ = "ROLE_TRIGGER_WRITE";

    public static final String ROLE_READ = "ROLE_ROLE_READ";
    public static final String ROLE_WRITE = "ROLE_ROLE_WRITE";

    public static final String FEED_READ = "ROLE_FEED_READ";
    public static final String FEED_WRITE = "ROLE_FEED_WRITE";

    public static final String ATCC_READ = "ROLE_ATCC_READ";
    public static final String VIDS_READ = "ROLE_VIDS_READ";
    public static final String ANPR_READ = "ROLE_ANPR_READ";
    public static final String PEP_READ = "ROLE_PEP_READ";

    public static final String PARK_WRITE= "ROLE_PARK_WRITE";
  }
}
