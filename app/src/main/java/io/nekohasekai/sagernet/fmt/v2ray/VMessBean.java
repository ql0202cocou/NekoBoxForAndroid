package io.nekohasekai.sagernet.fmt.v2ray;



import moe.matsuri.nb4a.utils.JavaUtil;

public class VMessBean extends StandardV2RayBean {

    public Integer alterId; // alterID == -1 --> VLESS

    @Override
    public void initializeDefaultValues() {
        super.initializeDefaultValues();

        alterId = alterId != null ? alterId : 0;

        if (alterId == -1) {
            encryption = JavaUtil.isNotBlank(encryption) ? encryption : "";
        } else {
            encryption = JavaUtil.isNotBlank(encryption) ? encryption : "auto";
        }
    }

}
