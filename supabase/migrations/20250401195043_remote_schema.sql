create table "public"."consent_records" (
    "id" uuid not null default 'f644148e-af0b-4755-ada4-8a9736056eea'::uuid,
    "user_id" uuid not null,
    "created_at" timestamp without time zone default now(),
    "consent_given" boolean not null,
    "consent_version" text not null,
    "consent_text" text,
    "ip_address" text,
    "user_agent" text,
    "study_id" text,
    "researcher_note" text
);


create table "public"."demographic_data" (
    "id" uuid not null default '5b0d8853-8565-4e22-a0d1-456c2c891b35'::uuid,
    "user_id" uuid not null,
    "created_at" timestamp without time zone default now(),
    "age_group" text not null,
    "gender" text not null,
    "ethnicity" text not null,
    "income" text not null,
    "ip_address" text,
    "user_agent" text,
    "study_id" text,
    "consent_version" text
);


CREATE UNIQUE INDEX consent_records_pkey ON public.consent_records USING btree (id);

CREATE UNIQUE INDEX demographic_data_pkey ON public.demographic_data USING btree (id);

alter table "public"."consent_records" add constraint "consent_records_pkey" PRIMARY KEY using index "consent_records_pkey";

alter table "public"."demographic_data" add constraint "demographic_data_pkey" PRIMARY KEY using index "demographic_data_pkey";

grant delete on table "public"."consent_records" to "anon";

grant insert on table "public"."consent_records" to "anon";

grant references on table "public"."consent_records" to "anon";

grant select on table "public"."consent_records" to "anon";

grant trigger on table "public"."consent_records" to "anon";

grant truncate on table "public"."consent_records" to "anon";

grant update on table "public"."consent_records" to "anon";

grant delete on table "public"."consent_records" to "authenticated";

grant insert on table "public"."consent_records" to "authenticated";

grant references on table "public"."consent_records" to "authenticated";

grant select on table "public"."consent_records" to "authenticated";

grant trigger on table "public"."consent_records" to "authenticated";

grant truncate on table "public"."consent_records" to "authenticated";

grant update on table "public"."consent_records" to "authenticated";

grant delete on table "public"."consent_records" to "service_role";

grant insert on table "public"."consent_records" to "service_role";

grant references on table "public"."consent_records" to "service_role";

grant select on table "public"."consent_records" to "service_role";

grant trigger on table "public"."consent_records" to "service_role";

grant truncate on table "public"."consent_records" to "service_role";

grant update on table "public"."consent_records" to "service_role";

grant delete on table "public"."demographic_data" to "anon";

grant insert on table "public"."demographic_data" to "anon";

grant references on table "public"."demographic_data" to "anon";

grant select on table "public"."demographic_data" to "anon";

grant trigger on table "public"."demographic_data" to "anon";

grant truncate on table "public"."demographic_data" to "anon";

grant update on table "public"."demographic_data" to "anon";

grant delete on table "public"."demographic_data" to "authenticated";

grant insert on table "public"."demographic_data" to "authenticated";

grant references on table "public"."demographic_data" to "authenticated";

grant select on table "public"."demographic_data" to "authenticated";

grant trigger on table "public"."demographic_data" to "authenticated";

grant truncate on table "public"."demographic_data" to "authenticated";

grant update on table "public"."demographic_data" to "authenticated";

grant delete on table "public"."demographic_data" to "service_role";

grant insert on table "public"."demographic_data" to "service_role";

grant references on table "public"."demographic_data" to "service_role";

grant select on table "public"."demographic_data" to "service_role";

grant trigger on table "public"."demographic_data" to "service_role";

grant truncate on table "public"."demographic_data" to "service_role";

grant update on table "public"."demographic_data" to "service_role";


