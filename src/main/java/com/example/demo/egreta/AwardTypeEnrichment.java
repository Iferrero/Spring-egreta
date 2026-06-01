package com.example.demo.egreta;

public enum AwardTypeEnrichment {
    AVR_AWARD("/dk/atira/pure/award/awardtypes/award/avr_award/award", "Ajuts específics del Vicerectorat", "Ayudas específicas del Vicerrectorado", "Specific grants from vice-chancellor’s office", "Ajudes competitives nacionals"),
    EXTERNAL_AGREEMENT("/dk/atira/pure/award/awardtypes/award/external_agreement", "Conveni extern a la UAB", "Convenio externo a la UAB", "External Agreement", "Externs UAB"),
    EXTERNAL_RESEARCH_GROUPS("/dk/atira/pure/award/awardtypes/award/external_research_groups_and_networks", "Grups i Xarxes de Recerca externs a la UAB", "Grupos y Redes de Investigación externos a la UAB", "External Research Groups and Networks", "Externs UAB"),
    FELLOWSHIP("/dk/atira/pure/award/awardtypes/award/fellowship/award", "Beques", "Becas", "Fellowship", "Ajudes competitives nacionals"),
    FIN_AWARD("/dk/atira/pure/award/awardtypes/award/fin_award/award", "Finançament Específic", "Financiación Especifica", "Specific Funding", "Ajudes competitives nacionals"),
    INFRASTRUCTURE("/dk/atira/pure/award/awardtypes/award/infraestructure/award", "Infraestructura", "Infrastructure", "Infraestructura", "Ajudes competitives nacionals"),
    INTERNATIONAL_EDU_PROG("/dk/atira/pure/award/awardtypes/award/international_educational_programmes/award", "Projectes Educatius Internacionals", "Proyectos Educativos Internacionales", "International Educational Programmes", "Ajudes competitives internacionals"),
    INTERNATIONAL_FELLOWSHIPS("/dk/atira/pure/award/awardtypes/award/international_fellowships/award", "Beques Internacionals", "Becas Internacionales", "International Fellowships", "Ajudes competitives internacionals"),
    INTERNATIONAL_AWARD("/dk/atira/pure/award/awardtypes/award/international_award/award", "Projectes d'investigació Internacionals", "Proyectos de investigación Internacionales", "International Research Awards", "Ajudes competitives internacionals"),
    MOBILITY_AWARDS("/dk/atira/pure/award/awardtypes/award/mobility_awards/award", "Mobilitat", "Mobiilidad", "Mobility", "Ajudes competitives nacionals"),
    OTHER_GRANTS("/dk/atira/pure/award/awardtypes/award/other_grants/award", "Accions complementàries i altres ajuts", "Acciones complementarias y otras ayudas", "Other grants", "Ajudes competitives nacionals"),
    OTHER_INTL_GRANTS("/dk/atira/pure/award/awardtypes/award/other_international_grants/award", "Altres ajuts internacionals", "Otras concesiones internacionales", "Other international grants", "Ajudes competitives internacionals"),
    RESEARCH_PROJECTS("/dk/atira/pure/award/awardtypes/award/research_projects_and_other_grants/award", "Projectes i Ajuts a la Recerca", "Proyectos y Ayudas de Investigación", "Research Projects and Other Grants", "Ajudes competitives nacionals"),
    RTC_AWARD("/dk/atira/pure/award/awardtypes/award/research_projects_and_other_grants/rtc_award", "RETOS COLABORACIÓN (RTC)", "RETOS COLABORACIÓN (RTC)", "RTC AWARD", "Ajudes competitives nacionals"),
    STAFF_TO_INCORPORATE("/dk/atira/pure/award/awardtypes/award/staff_to_incorporate/award", "Incorporació de Personal", "Incorporación de Personal", "Staff to incorporate", "Ajudes competitives nacionals"),
    UAB_EXTERNAL("/dk/atira/pure/award/awardtypes/award/uab_external_research_projects_and_other_external_grants_subtypes/external_award", "Projectes de Recerca i altres tipus d'ajuts Externs a la UAB", "Proyectos de Investigación y otros tipos de ayudas Externas a la UAB", "UAB External Research Projects and Other External Grants subtypes", "Externs UAB"),
    UAB_RESEARCH_GROUP("/dk/atira/pure/award/awardtypes/award/uab_research_group/award_renovation", "Grups i Xarxes de Recerca", "Grupos y Redes de Investigación", "Research Group and Networks", "Ajudes competitives nacionals"),
    AGREEMENT("/dk/atira/pure/award/awardtypes/award/agreement/award_agreement", null, null, null, "Ajudes no competitives nacionals");

    public final String uri;
    public final String ca;
    public final String es;
    public final String en;
    public final String categoria;

    AwardTypeEnrichment(String uri, String ca, String es, String en, String categoria) {
        this.uri = uri;
        this.ca = ca;
        this.es = es;
        this.en = en;
        this.categoria = categoria;
    }
}
