package io.github.vihuynh72.brownie.core.detail;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * The canonical saved-detail keys, with the words a person sees for each
 * and the flag that says whether the value is ever sealed rather than
 * stored in plain text. The slug is what {@code saved_detail.key} stores
 * and what the classifier returns when it names a fill's answer.
 */
public enum DetailKey {
    PERSON_FULL_NAME("person.full_name", "Full name", DetailGroup.IDENTITY, false),
    PERSON_FIRST_NAME("person.first_name", "First name", DetailGroup.IDENTITY, false),
    PERSON_MIDDLE_NAME("person.middle_name", "Middle name", DetailGroup.IDENTITY, false),
    PERSON_LAST_NAME("person.last_name", "Last name", DetailGroup.IDENTITY, false),
    PERSON_PREFERRED_NAME("person.preferred_name", "Preferred name", DetailGroup.IDENTITY, false),
    PERSON_DATE_OF_BIRTH("person.date_of_birth", "Date of birth", DetailGroup.IDENTITY, false),
    CONTACT_EMAIL("contact.email", "Email", DetailGroup.CONTACT, false),
    CONTACT_PHONE_MOBILE("contact.phone_mobile", "Mobile phone", DetailGroup.CONTACT, false),
    CONTACT_PHONE_HOME("contact.phone_home", "Home phone", DetailGroup.CONTACT, false),
    ADDRESS_LINE1("address.line1", "Street address", DetailGroup.ADDRESS, false),
    ADDRESS_UNIT("address.unit", "Apartment, suite or unit", DetailGroup.ADDRESS, false),
    ADDRESS_CITY("address.city", "City", DetailGroup.ADDRESS, false),
    ADDRESS_STATE("address.state", "State", DetailGroup.ADDRESS, false),
    ADDRESS_POSTAL_CODE("address.postal_code", "ZIP or postal code", DetailGroup.ADDRESS, false),
    ADDRESS_COUNTRY("address.country", "Country", DetailGroup.ADDRESS, false),
    WORK_EMPLOYER("work.employer", "Employer", DetailGroup.WORK, false),
    WORK_JOB_TITLE("work.job_title", "Job title", DetailGroup.WORK, false),
    WORK_START_DATE("work.start_date", "Start date", DetailGroup.WORK, false),
    WORK_EMPLOYER_PHONE("work.employer_phone", "Employer's phone", DetailGroup.WORK, false),
    WORK_EMPLOYER_ADDRESS("work.employer_address", "Employer's address", DetailGroup.WORK, false),
    INCOME_MONTHLY_GROSS("income.monthly_gross", "Monthly gross income", DetailGroup.INCOME, false),
    INCOME_ANNUAL_GROSS("income.annual_gross", "Annual gross income", DetailGroup.INCOME, false),
    EDUCATION_HIGHEST_LEVEL("education.highest_level", "Highest education", DetailGroup.EDUCATION, false),
    EDUCATION_SCHOOL("education.school", "School", DetailGroup.EDUCATION, false),
    EDUCATION_GRADUATION_YEAR("education.graduation_year", "Graduation year", DetailGroup.EDUCATION, false),
    HOUSEHOLD_SIZE("household.size", "Household size", DetailGroup.HOUSEHOLD, false),
    HOUSEHOLD_MARITAL_STATUS("household.marital_status", "Marital status", DetailGroup.HOUSEHOLD, false),
    EMERGENCY_CONTACT_NAME("emergency.contact_name", "Emergency contact name", DetailGroup.EMERGENCY, false),
    EMERGENCY_CONTACT_PHONE("emergency.contact_phone", "Emergency contact phone", DetailGroup.EMERGENCY, false),
    EMERGENCY_CONTACT_RELATIONSHIP("emergency.contact_relationship", "Emergency contact relationship", DetailGroup.EMERGENCY, false),
    SENSITIVE_SSN("sensitive.ssn", "Social security number", DetailGroup.SENSITIVE, true),
    SENSITIVE_PASSPORT_NUMBER("sensitive.passport_number", "Passport number", DetailGroup.SENSITIVE, true),
    SENSITIVE_ALIEN_NUMBER("sensitive.alien_number", "Alien registration number", DetailGroup.SENSITIVE, true),
    SENSITIVE_DRIVERS_LICENSE_NUMBER("sensitive.drivers_license_number", "Driver's license number", DetailGroup.SENSITIVE, true),
    SENSITIVE_BANK_ACCOUNT("sensitive.bank_account", "Bank account number", DetailGroup.SENSITIVE, true),
    SENSITIVE_BANK_ROUTING("sensitive.bank_routing", "Bank routing number", DetailGroup.SENSITIVE, true),
    SENSITIVE_INSURANCE_MEMBER_ID("sensitive.insurance_member_id", "Insurance member ID", DetailGroup.SENSITIVE, true),
    SENSITIVE_MEDICAL_CONDITIONS("sensitive.medical_conditions", "Medical conditions", DetailGroup.SENSITIVE, true),
    SENSITIVE_MEDICATIONS("sensitive.medications", "Medications", DetailGroup.SENSITIVE, true);

    private final String key;
    private final String label;
    private final DetailGroup group;
    private final boolean sensitive;

    DetailKey(String key, String label, DetailGroup group, boolean sensitive) {
        this.key = key;
        this.label = label;
        this.group = group;
        this.sensitive = sensitive;
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    public DetailGroup group() {
        return group;
    }

    public boolean sensitive() {
        return sensitive;
    }

    public static Optional<DetailKey> find(String key) {
        return Arrays.stream(values()).filter(each -> each.key.equals(key)).findFirst();
    }

    public static List<DetailKey> sensitiveKeys() {
        return Arrays.stream(values()).filter(DetailKey::sensitive).toList();
    }
}
