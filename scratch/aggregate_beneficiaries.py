import pymongo

client = pymongo.MongoClient("mongodb://myUserAdmin:Mongoplex.00@ymir.uab.cat:8000/admin")
db = client["kraken"]

col = db["Applications"]

# Let's run a test aggregation to count female beneficiaries (with fallback to PI if no beneficiary role is present)
# for accepted applications.

pipeline = [
    # 1. Project basic fields
    {"$project": {
        "fundingUuid": "$fundingOpportunity.uuid",
        "replyText": {"$toLower": {"$ifNull": [
            "$funderReply.key",
            {"$ifNull": [
                "$funderReply.description.en_GB",
                {"$ifNull": [
                    "$funderReply.description.es_ES",
                    {"$ifNull": [
                        "$funderReply.description.ca_ES",
                        {"$ifNull": [
                            "$funderReply.en_GB",
                            {"$ifNull": [
                                "$funderReply.es_ES",
                                {"$ifNull": [
                                    "$funderReply.ca_ES",
                                    {"$ifNull": ["$funderReply", ""]}
                                ]}
                            ]}
                        ]}
                    ]}
                ]}
            ]}
        ]}},
        "applicants": 1
    }},
    # 2. Project accepted flag and beneficiary/PI uuids
    {"$project": {
        "fundingUuid": 1,
        "isAccepted": {
            "$cond": [
                {"$and": [
                    {"$not": [{"$regexMatch": {"input": "$replyText", "regex": "reject|deneg|declin|desestim|rebutj|unfavorable|refused|not funded|no funded"}}]},
                    {"$regexMatch": {"input": "$replyText", "regex": "accept|approved|award|granted|conced|aprobad|admis|seleccion|favorable"}}
                ]},
                1,
                0
            ]
        },
        # Get beneficiary UUIDs
        "benUuids": {
            "$let": {
                "vars": {
                    "applicantsArr": {"$cond": [{"$isArray": "$applicants"}, "$applicants", []]}
                },
                "in": {
                    "$let": {
                        "vars": {
                            "bens": {
                                "$filter": {
                                    "input": "$$applicantsArr",
                                    "as": "app",
                                    "cond": {
                                        "$or": [
                                            {"$in": ["$$app.role.uri", [
                                                "/dk/atira/pure/application/roles/application/ben",
                                                "/dk/atira/pure/application/roles/application/bec",
                                                "/dk/atira/pure/application/roles/application/can",
                                                "/dk/atira/pure/application/roles/application/applicant"
                                            ]]},
                                            {"$in": [{"$toLower": "$$app.role.term.ca_ES"}, ["beneficiari/a", "becari/a", "candidat/a", "beneficiario/a", "becario/a", "candidato/a"]]}
                                        ]
                                    }
                                }
                            },
                            "pis": {
                                "$filter": {
                                    "input": "$$applicantsArr",
                                    "as": "app",
                                    "cond": {"$eq": ["$$app.role.uri", "/dk/atira/pure/application/roles/application/pi"]}
                                }
                            }
                        },
                        "in": {
                            "$cond": [
                                {"$gt": [{"$size": "$$bens"}, 0]},
                                "$$bens.person.uuid",
                                {"$cond": [
                                    {"$gt": [{"$size": "$$pis"}, 0]},
                                    "$$pis.person.uuid",
                                    "$$applicantsArr.person.uuid"
                                ]}
                            ]
                        }
                    }
                }
            }
        }
    }},
    # Get the first UUID from the array (since lookup localField expects a single value or array, we can use localField directly)
    {"$project": {
        "fundingUuid": 1,
        "isAccepted": 1,
        "benUuid": {"$arrayElemAt": ["$benUuids", 0]}
    }},
    # 3. Lookup person gender
    {"$lookup": {
        "from": "Persons",
        "localField": "benUuid",
        "foreignField": "uuid",
        "as": "person"
    }},
    {"$project": {
        "fundingUuid": 1,
        "isAccepted": 1,
        "gender": {"$arrayElemAt": ["$person.gender.uri", 0]},
        "sex": {"$arrayElemAt": ["$person.sex.term.en_GB", 0]}
    }},
    {"$project": {
        "fundingUuid": 1,
        "isAccepted": 1,
        "isFemale": {
            "$cond": [
                {"$or": [
                    {"$regexMatch": {"input": {"$toLower": {"$ifNull": ["$gender", ""]}}, "regex": "female|mujer|femeni|dona"}},
                    {"$regexMatch": {"input": {"$toLower": {"$ifNull": ["$sex", ""]}}, "regex": "female|mujer|femeni|dona"}}
                ]},
                1,
                0
            ]
        }
    }},
    # Group by fundingOpportunity
    {"$group": {
        "_id": "$fundingUuid",
        "accepted": {"$sum": "$isAccepted"},
        "acceptedFemaleBeneficiaries": {"$sum": {"$cond": [{"$and": [{"$eq": ["$isAccepted", 1]}, {"$eq": ["$isFemale", 1]}]}, 1, 0]}}
    }}
]

results = list(col.aggregate(pipeline))
print("Female beneficiaries per funding opportunity (with fallback to PI):")
for r in sorted(results, key=lambda x: x.get("acceptedFemaleBeneficiaries", 0), reverse=True)[:10]:
    # Look up funding title
    fo = db["FundingOpportunities"].find_one({"uuid": r["_id"]})
    title = fo.get("title", {}).get("ca_ES") if fo else "Unknown"
    print(f"Title: {title} | Accepted: {r['accepted']} | Female Beneficiaries: {r['acceptedFemaleBeneficiaries']}")

client.close()
