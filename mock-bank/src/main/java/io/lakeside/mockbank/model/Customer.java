package io.lakeside.mockbank.model;

public record Customer(String customerId, String displayName, Address registeredAddress) {

    public record Address(String line1, String city, String pincode) {

        /** Only a masked form of the address ever leaves the system of record. */
        public String masked() {
            return line1.substring(0, Math.min(4, line1.length())) + "****, " + city + " - " + pincode.substring(0, 3) + "***";
        }
    }
}
