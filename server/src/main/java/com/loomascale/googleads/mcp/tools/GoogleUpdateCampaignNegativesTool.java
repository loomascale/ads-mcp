package com.loomascale.googleads.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.loomascale.googleads.client.dto.GoogleBrandDto;
import com.loomascale.googleads.client.dto.GoogleBrandExclusionDto;
import com.loomascale.googleads.client.dto.GoogleCampaignDto;
import com.loomascale.googleads.client.dto.GoogleCampaignNegativeKeywordDto;
import com.loomascale.googleads.client.dto.GoogleCampaignNegativesDto;
import com.loomascale.googleads.client.dto.GoogleNegativeKeywordSpec;
import com.loomascale.googleads.mcp.service.GoogleAdsEditSupport;
import com.loomascale.googleads.mcp.service.GoogleAdsService;
import com.loomascale.mcp.audit.McpWriteAuditService;
import com.loomascale.mcp.audit.WriteKind;
import com.loomascale.mcp.schema.McpSchemas;
import com.loomascale.mcp.spi.AdsConnection;
import com.loomascale.mcp.spi.AdsPlatforms;
import com.loomascale.mcp.spi.ProductBranding;
import com.loomascale.mcp.tool.AdsTool;
import com.loomascale.mcp.tool.McpAuthGate;
import com.loomascale.mcp.tool.McpToolException;
import com.loomascale.mcp.tool.ToolAnnotations;
import com.loomascale.mcp.tool.ToolResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

// What a campaign is told NOT to serve on. Two mechanisms, one tool, because "stop my
// Performance Max eating my brand searches" has two correct fixes and the model has to be
// able to reach both from one thought: a negative keyword blocks the exact query, and a brand
// exclusion blocks every variation and misspelling of a brand, which a negative keyword
// cannot do.
//
// The compromise worth knowing about: Google models brand exclusions as a BRANDS SharedSet,
// which is a shared object by design, while this tool's contract is per-campaign. A brand
// list attached to more than one campaign is therefore refused rather than edited — the same
// refusal google_update_budget makes for a shared budget and google_update_campaign_bidding
// for a portfolio strategy.
@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleUpdateCampaignNegativesTool implements AdsTool {

  private final McpAuthGate authGate;
  private final GoogleAdsService adsService;
  private final GoogleAdsEditSupport support;
  private final McpWriteAuditService audit;
  private final ProductBranding branding;
  private final ObjectMapper objectMapper;

  @Override
  public String name() {
    return "google_update_campaign_negatives";
  }

  @Override
  public String description() {
    return "Stop a Google Ads campaign from serving on queries and brands you do not want it on."
        + " Adds campaign-level negative keywords with EXACT, PHRASE or BROAD match, and for"
        + " Performance Max and search campaigns adds brand exclusions — a brand list Google"
        + " matches against every variation and misspelling of a brand, which plain negative"
        + " keywords cannot do. Together these are the fix for a Performance Max campaign"
        + " cannibalizing your own brand search: exclude your brand to push branded traffic back"
        + " to the search campaign that was already winning it, or exclude a competitor's brand"
        + " to stop paying for their name. Also removes negative keywords, detaches a brand list"
        + " from a campaign, and takes individual brands out of a list. Call it with no changes"
        + " to read what a campaign already excludes. Refuses to edit a brand list that is"
        + " shared with other campaigns. For ad-group-level negatives use google_add_keywords"
        + " and google_remove_keywords; for locations and languages use"
        + " google_update_campaign_targeting.";
  }

  @Override
  public ObjectNode inputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "campaign_id", "string", "Campaign to change.");
    ObjectNode keywords =
        McpSchemas.objectArrayProp(
            schema,
            "negative_keywords",
            "Negative keywords to ADD, at most "
                + GoogleAdsEditSupport.MAX_NEGATIVE_KEYWORDS
                + " per call. An entry may be a plain string or an object with a text and a"
                + " match_type. Adding is additive: keywords the campaign already excludes are"
                + " skipped rather than duplicated.");
    McpSchemas.prop(keywords, "text", "string", "Query text to exclude.");
    ObjectNode matchType =
        McpSchemas.prop(
            keywords,
            "match_type",
            "string",
            "BROAD (the default) blocks the phrase and its variants in any order, PHRASE blocks"
                + " the phrase in order, EXACT blocks only that exact query. A broad negative is"
                + " the blunt one: it also blocks queries you may want.");
    McpSchemas.enumValues(matchType, GoogleAdsEditSupport.MATCH_TYPES);
    McpSchemas.stringArrayProp(
        schema,
        "brands",
        "Brand names to exclude, at most "
            + GoogleAdsEditSupport.MAX_BRANDS
            + " per call — e.g. your own brand, or a competitor's. Google resolves each name to"
            + " a brand it recognizes and refuses a name it does not, listing what it suggests"
            + " instead. Performance Max and search campaigns only.");
    McpSchemas.stringArrayProp(
        schema,
        "remove_criterion_ids",
        "Criterion ids to remove, as reported by this tool. Removes negative keywords, and"
            + " detaches a brand list from the campaign — the list itself survives in the account"
            + " for reuse.");
    McpSchemas.stringArrayProp(
        schema,
        "remove_brands",
        "Brand names or entity ids to take OUT of the campaign's brand list. The list stays"
            + " attached; emptying it excludes nothing.");
    McpSchemas.prop(
        schema, "customer_id", "string", "Google Ads customer id. Defaults to the selected one.");
    McpSchemas.required(schema, "campaign_id");
    return schema;
  }

  @Override
  public ObjectNode outputSchema() {
    ObjectNode schema = McpSchemas.object(objectMapper);
    McpSchemas.prop(schema, "campaignId", "string", "Campaign that was read or changed.");
    ObjectNode keyword =
        McpSchemas.objectArrayProp(
            schema, "negativeKeywords", "Negative keywords the campaign now excludes.");
    McpSchemas.nullableProp(keyword, "criterionId", "string", "Handle for removing it.");
    McpSchemas.nullableProp(keyword, "text", "string", "Query text excluded.");
    McpSchemas.nullableProp(keyword, "matchType", "string", "EXACT, PHRASE or BROAD.");
    ObjectNode exclusion =
        McpSchemas.objectArrayProp(
            schema, "brandExclusions", "Brand lists attached to the campaign.");
    McpSchemas.nullableProp(
        exclusion, "criterionId", "string", "Handle for detaching the list from the campaign.");
    McpSchemas.nullableProp(exclusion, "sharedSetId", "string", "Id of the brand list itself.");
    McpSchemas.nullableProp(exclusion, "sharedSetName", "string", "Name of the brand list.");
    McpSchemas.stringArrayProp(exclusion, "brands", "Brands inside the list.");
    McpSchemas.stringArrayProp(
        exclusion,
        "otherCampaigns",
        "Other campaigns this same list is applied to. Non-empty means the list is shared and"
            + " this tool will not edit its members.");
    McpSchemas.prop(schema, "added", "integer", "How many criteria or brands were created.");
    McpSchemas.prop(schema, "removed", "integer", "How many were deleted.");
    McpSchemas.stringArrayProp(
        schema, "skipped", "What was already in place, and so was not written again.");
    McpSchemas.prop(
        schema,
        "brandListCreated",
        "boolean",
        "True when this call built a new brand list for the campaign.");
    McpSchemas.required(
        schema, "campaignId", "negativeKeywords", "brandExclusions", "added", "removed");
    return schema;
  }

  @Override
  public ToolAnnotations annotations() {
    // Destructive: removing a negative keyword or a brand exclusion re-opens traffic the
    // campaign was not serving on, which costs money, and criterion removal is permanent —
    // re-adding the same text creates a fresh criterion with no history. Idempotent: adds skip
    // what is already excluded, an existing brand list created here is reused rather than
    // duplicated, and a removal of something already gone is skipped rather than failing.
    return ToolAnnotations.write(true, true);
  }

  @Override
  public boolean requiresWriteScope() {
    return true;
  }

  @Override
  public ToolResult execute(String userId, JsonNode args) {
    AdsConnection connection = authGate.requireWriteConnection(userId, AdsPlatforms.GOOGLE_ADS);
    if (!args.hasNonNull("campaign_id")) {
      throw new McpToolException("campaign_id is required.");
    }
    String campaignId = args.get("campaign_id").asText();

    List<GoogleNegativeKeywordSpec> keywordsToAdd =
        args.has("negative_keywords")
            ? support.parseNegativeKeywordSpecs(
                args.get("negative_keywords"), GoogleAdsEditSupport.MAX_NEGATIVE_KEYWORDS)
            : List.of();
    List<String> criterionIdsToRemove = strings(args, "remove_criterion_ids");
    List<String> brandsToRemove = strings(args, "remove_brands");
    boolean addsBrands = args.has("brands");

    String token = adsService.decryptToken(connection);
    String customerId =
        adsService.resolveCustomerId(
            connection, args.hasNonNull("customer_id") ? args.get("customer_id").asText() : null);
    String loginCustomerId = adsService.loginCustomerId(connection, customerId);

    GoogleCampaignDto campaign =
        support.requireCampaign(connection, token, customerId, loginCustomerId, campaignId);
    if (addsBrands
        && !GoogleAdsEditSupport.BRAND_EXCLUSION_CHANNELS.contains(campaign.channelType())) {
      throw new McpToolException(
          "Campaign \""
              + campaign.name()
              + "\" is a "
              + campaign.channelType()
              + " campaign, and Google only supports brand exclusions on Performance Max and"
              + " search campaigns. Use negative_keywords instead, which work everywhere.");
    }

    // Account-wide, so the shared-list question can be answered.
    GoogleCampaignNegativesDto accountNegatives =
        adsService.call(
            connection,
            () ->
                adsService
                    .client()
                    .listCampaignNegatives(token, customerId, loginCustomerId, null));
    List<GoogleCampaignNegativeKeywordDto> currentKeywords =
        accountNegatives.keywords().stream()
            .filter(keyword -> campaignId.equals(keyword.campaignId()))
            .toList();
    GoogleBrandExclusionDto currentExclusion =
        accountNegatives.brandExclusions().stream()
            .filter(exclusion -> campaignId.equals(exclusion.campaignId()))
            .findFirst()
            .orElse(null);

    List<String> skipped = new ArrayList<>();
    List<GoogleNegativeKeywordSpec> newKeywords = new ArrayList<>();
    for (GoogleNegativeKeywordSpec spec : keywordsToAdd) {
      boolean present =
          currentKeywords.stream()
              .anyMatch(
                  keyword ->
                      spec.text().equalsIgnoreCase(keyword.text())
                          && spec.matchType().equals(keyword.matchType()));
      if (present) {
        skipped.add("\"" + spec.text() + "\" (" + spec.matchType() + ") was already excluded");
      } else {
        newKeywords.add(spec);
      }
    }

    List<String> validRemovals = new ArrayList<>();
    Set<String> knownCriterionIds = new LinkedHashSet<>();
    currentKeywords.forEach(keyword -> knownCriterionIds.add(keyword.criterionId()));
    if (currentExclusion != null) {
      knownCriterionIds.add(currentExclusion.criterionId());
    }
    for (String criterionId : criterionIdsToRemove) {
      if (knownCriterionIds.contains(criterionId)) {
        validRemovals.add(criterionId);
      } else {
        skipped.add("criterion " + criterionId + " is not on this campaign, so nothing to remove");
      }
    }

    // Brand membership is a second read, and only when there is a list to read.
    List<GoogleBrandDto> currentBrands =
        currentExclusion == null
            ? List.of()
            : adsService.call(
                connection,
                () ->
                    adsService
                        .client()
                        .listSharedSetBrands(
                            token, customerId, loginCustomerId, currentExclusion.sharedSetId()));
    List<String> otherCampaigns =
        currentExclusion == null
            ? List.of()
            : accountNegatives.brandExclusions().stream()
                .filter(exclusion -> !campaignId.equals(exclusion.campaignId()))
                .filter(exclusion -> currentExclusion.sharedSetId().equals(exclusion.sharedSetId()))
                .map(GoogleBrandExclusionDto::campaignId)
                .toList();

    boolean editsBrandMembers = addsBrands || !brandsToRemove.isEmpty();
    if (editsBrandMembers && currentExclusion != null && !otherCampaigns.isEmpty()) {
      // The compromise stated in the class comment: Google's brand list is a shared object
      // and this tool's contract is per-campaign, so a shared list is refused rather than
      // silently changing campaigns nobody named.
      throw new McpToolException(
          "Campaign \""
              + campaign.name()
              + "\" excludes brands through the brand list \""
              + currentExclusion.sharedSetName()
              + "\", which is also applied to "
              + otherCampaigns.size()
              + " other campaign(s) (ids "
              + String.join(", ", otherCampaigns)
              + "). Adding or removing a brand there would change those campaigns too, so"
              + " a shared brand list is not edited here. Edit it in Google Ads directly,"
              + " or detach it from this campaign with remove_criterion_ids and let this server"
              + " build a list for this campaign alone.");
    }

    List<GoogleBrandDto> resolvedBrands =
        addsBrands
            ? support.resolveBrands(
                connection, token, customerId, loginCustomerId, args.get("brands"))
            : List.of();
    List<GoogleBrandDto> newBrands = new ArrayList<>();
    for (GoogleBrandDto brand : resolvedBrands) {
      if (currentBrands.stream().anyMatch(current -> brand.entityId().equals(current.entityId()))) {
        skipped.add("brand " + brand.displayName() + " was already excluded");
      } else {
        newBrands.add(brand);
      }
    }
    List<GoogleBrandDto> brandMembersToRemove = new ArrayList<>();
    for (String wanted : brandsToRemove) {
      GoogleBrandDto match =
          currentBrands.stream()
              .filter(
                  brand ->
                      wanted.equalsIgnoreCase(brand.displayName())
                          || wanted.equals(brand.entityId()))
              .findFirst()
              .orElse(null);
      if (match == null) {
        skipped.add("brand " + wanted + " is not in the list, so nothing to remove");
      } else {
        brandMembersToRemove.add(match);
      }
    }

    boolean changesSomething =
        !newKeywords.isEmpty()
            || !validRemovals.isEmpty()
            || !newBrands.isEmpty()
            || !brandMembersToRemove.isEmpty();
    String argsSummary =
        "campaign="
            + campaignId
            + ";addKeywords="
            + newKeywords.size()
            + ";addBrands="
            + newBrands.size()
            + ";removeCriteria="
            + validRemovals.size()
            + ";removeBrands="
            + brandMembersToRemove.size();
    log.debug("google_update_campaign_negatives user={} {}", userId, argsSummary);

    if (!changesSomething) {
      // Reading is a legitimate use of this tool, so an empty call is a report rather than a
      // refusal.
      return ToolResult.ok(
          readText(
              campaign, currentKeywords, currentExclusion, currentBrands, otherCampaigns, skipped),
          structured(
              campaignId,
              currentKeywords,
              currentExclusion,
              currentBrands,
              otherCampaigns,
              0,
              0,
              skipped,
              false));
    }

    // Created resources in order, so a failure part-way through the brand chain can be
    // unwound in reverse.
    List<String> created = new ArrayList<>();
    boolean brandListCreated = false;
    try {
      // Removals first: a criterion's keyword text is immutable, so "change a match type" is
      // remove-then-add, and doing it in this order lets one call express it.
      if (!validRemovals.isEmpty()) {
        adsService.call(
            connection,
            () -> {
              adsService
                  .client()
                  .removeCampaignCriteria(
                      token, customerId, loginCustomerId, campaignId, validRemovals);
              return null;
            });
      }
      for (GoogleBrandDto brand : brandMembersToRemove) {
        adsService.call(
            connection,
            () -> {
              adsService
                  .client()
                  .removeResource(
                      token,
                      customerId,
                      loginCustomerId,
                      "customers/"
                          + customerId
                          + "/sharedCriteria/"
                          + currentExclusion.sharedSetId()
                          + "~"
                          + brand.sharedCriterionId());
              return null;
            });
      }
      if (!newKeywords.isEmpty()) {
        adsService.call(
            connection,
            () ->
                adsService
                    .client()
                    .addCampaignNegativeKeywords(
                        token,
                        customerId,
                        loginCustomerId,
                        "customers/" + customerId + "/campaigns/" + campaignId,
                        newKeywords));
      }
      if (!newBrands.isEmpty()) {
        String sharedSetResourceName;
        if (currentExclusion != null && validRemovals.contains(currentExclusion.criterionId())) {
          // The caller detached the list in this same call, so build a fresh one rather than
          // adding brands to something no longer attached.
          sharedSetResourceName = null;
        } else if (currentExclusion != null) {
          sharedSetResourceName =
              "customers/" + customerId + "/sharedSets/" + currentExclusion.sharedSetId();
        } else {
          sharedSetResourceName = null;
        }
        String targetSet = sharedSetResourceName;
        if (targetSet == null) {
          targetSet =
              adsService.call(
                  connection,
                  () ->
                      adsService
                          .client()
                          .createBrandSharedSet(
                              token,
                              customerId,
                              loginCustomerId,
                              branding.productName()
                                  + " brand exclusions — "
                                  + campaign.name()
                                  + " ("
                                  + campaignId
                                  + ")"));
          created.add(targetSet);
          brandListCreated = true;
        }
        String setForLambda = targetSet;
        adsService.call(
            connection,
            () ->
                adsService
                    .client()
                    .addSharedSetBrands(
                        token,
                        customerId,
                        loginCustomerId,
                        setForLambda,
                        newBrands.stream().map(GoogleBrandDto::entityId).toList()));
        if (brandListCreated) {
          String attached =
              adsService.call(
                  connection,
                  () ->
                      adsService
                          .client()
                          .addBrandListCriterion(
                              token,
                              customerId,
                              loginCustomerId,
                              "customers/" + customerId + "/campaigns/" + campaignId,
                              setForLambda));
          created.add(attached);
        }
      }
    } catch (RuntimeException e) {
      rollback(connection, token, customerId, loginCustomerId, created);
      audit.record(
          userId, name(), WriteKind.UPDATE, argsSummary, campaignId, false, e.getMessage());
      throw e;
    }

    // One re-read, so the result states what the campaign actually excludes now rather than
    // what this call believes it wrote.
    GoogleCampaignNegativesDto after =
        adsService.call(
            connection,
            () ->
                adsService
                    .client()
                    .listCampaignNegatives(token, customerId, loginCustomerId, campaignId));
    GoogleBrandExclusionDto exclusionAfter =
        after.brandExclusions().stream().findFirst().orElse(null);
    List<GoogleBrandDto> brandsAfter =
        exclusionAfter == null
            ? List.of()
            : adsService.call(
                connection,
                () ->
                    adsService
                        .client()
                        .listSharedSetBrands(
                            token, customerId, loginCustomerId, exclusionAfter.sharedSetId()));
    int added = newKeywords.size() + newBrands.size();
    int removed = validRemovals.size() + brandMembersToRemove.size();
    audit.record(userId, name(), WriteKind.UPDATE, argsSummary, campaignId, true, null);
    audit.alert(
        "<b>MCP Ads: google_update_campaign_negatives</b>\nUser: "
            + userId
            + "\nCampaign: "
            + campaign.name()
            + " ("
            + campaignId
            + ")\nAdded: "
            + added
            + "\nRemoved: "
            + removed
            + (brandListCreated ? "\nBuilt a new brand list" : ""));

    return ToolResult.ok(
        changeText(
            campaign,
            newKeywords,
            newBrands,
            validRemovals,
            brandMembersToRemove,
            skipped,
            brandListCreated),
        structured(
            campaignId,
            after.keywords(),
            exclusionAfter,
            brandsAfter,
            List.of(),
            added,
            removed,
            skipped,
            brandListCreated));
  }

  // Best effort, in reverse order. It can itself fail, which is why it logs rather than
  // throwing over the original error.
  private void rollback(
      AdsConnection connection,
      String token,
      String customerId,
      String loginCustomerId,
      List<String> created) {
    for (int i = created.size() - 1; i >= 0; i--) {
      String resourceName = created.get(i);
      try {
        adsService.call(
            connection,
            () -> {
              adsService.client().removeResource(token, customerId, loginCustomerId, resourceName);
              return null;
            });
      } catch (RuntimeException e) {
        log.warn("Could not roll back {}: {}", resourceName, e.getMessage());
      }
    }
  }

  private List<String> strings(JsonNode args, String field) {
    if (!args.has(field)) {
      return List.of();
    }
    JsonNode node = args.get(field);
    if (!node.isArray()) {
      throw new McpToolException(field + " must be an array.");
    }
    List<String> values = new ArrayList<>();
    for (JsonNode entry : node) {
      String value = entry.asText("").trim();
      if (!value.isBlank()) {
        values.add(value);
      }
    }
    return values;
  }

  private ObjectNode structured(
      String campaignId,
      List<GoogleCampaignNegativeKeywordDto> keywords,
      GoogleBrandExclusionDto exclusion,
      List<GoogleBrandDto> brands,
      List<String> otherCampaigns,
      int added,
      int removed,
      List<String> skipped,
      boolean brandListCreated) {
    ObjectNode structured = objectMapper.createObjectNode();
    structured.put("campaignId", campaignId);
    ArrayNode keywordArray = structured.putArray("negativeKeywords");
    for (GoogleCampaignNegativeKeywordDto keyword : keywords) {
      ObjectNode node = keywordArray.addObject();
      node.put("criterionId", keyword.criterionId());
      node.put("text", keyword.text());
      node.put("matchType", keyword.matchType());
    }
    ArrayNode exclusionArray = structured.putArray("brandExclusions");
    if (exclusion != null) {
      ObjectNode node = exclusionArray.addObject();
      node.put("criterionId", exclusion.criterionId());
      node.put("sharedSetId", exclusion.sharedSetId());
      node.put("sharedSetName", exclusion.sharedSetName());
      ArrayNode brandArray = node.putArray("brands");
      brands.forEach(brand -> brandArray.add(brand.displayName()));
      ArrayNode otherArray = node.putArray("otherCampaigns");
      otherCampaigns.forEach(otherArray::add);
    }
    structured.put("added", added);
    structured.put("removed", removed);
    skipped.forEach(structured.putArray("skipped")::add);
    structured.put("brandListCreated", brandListCreated);
    return structured;
  }

  private String readText(
      GoogleCampaignDto campaign,
      List<GoogleCampaignNegativeKeywordDto> keywords,
      GoogleBrandExclusionDto exclusion,
      List<GoogleBrandDto> brands,
      List<String> otherCampaigns,
      List<String> skipped) {
    StringBuilder text = new StringBuilder();
    text.append("Campaign ").append(campaign.name()).append(" excludes:\n");
    if (keywords.isEmpty()) {
      text.append("  no negative keywords\n");
    }
    for (GoogleCampaignNegativeKeywordDto keyword : keywords) {
      text.append("  - \"")
          .append(keyword.text())
          .append("\" (")
          .append(keyword.matchType())
          .append(", criterion ")
          .append(keyword.criterionId())
          .append(")\n");
    }
    if (exclusion == null) {
      text.append("  no brand exclusions\n");
    } else {
      text.append("  brand list \"").append(exclusion.sharedSetName()).append("\": ");
      text.append(
          brands.isEmpty()
              ? "empty, which excludes nothing"
              : String.join(", ", brands.stream().map(GoogleBrandDto::displayName).toList()));
      text.append("\n");
      if (!otherCampaigns.isEmpty()) {
        text.append("  (that list is shared with ")
            .append(otherCampaigns.size())
            .append(" other campaign(s), so this server will not edit its members)\n");
      }
    }
    skipped.forEach(note -> text.append("  ").append(note).append("\n"));
    return text.toString();
  }

  private String changeText(
      GoogleCampaignDto campaign,
      List<GoogleNegativeKeywordSpec> addedKeywords,
      List<GoogleBrandDto> addedBrands,
      List<String> removedCriteria,
      List<GoogleBrandDto> removedBrands,
      List<String> skipped,
      boolean brandListCreated) {
    StringBuilder text = new StringBuilder();
    text.append("Campaign ").append(campaign.name()).append(":\n");
    for (GoogleNegativeKeywordSpec spec : addedKeywords) {
      text.append("  + negative \"")
          .append(spec.text())
          .append("\" (")
          .append(spec.matchType())
          .append(")\n");
    }
    for (GoogleBrandDto brand : addedBrands) {
      text.append("  + brand exclusion ").append(brand.displayName()).append("\n");
    }
    for (String criterionId : removedCriteria) {
      text.append("  - criterion ").append(criterionId).append("\n");
    }
    for (GoogleBrandDto brand : removedBrands) {
      text.append("  - brand ").append(brand.displayName()).append("\n");
    }
    skipped.forEach(note -> text.append("  ").append(note).append("\n"));
    if (brandListCreated) {
      text.append(
          "Built a new brand list for this campaign and attached it, so the exclusions apply"
              + " here and nowhere else.\n");
    }
    text.append(
        "Exclusions take effect on new auctions, not retroactively. Check the search terms"
            + " report in a few days to see what is still getting through.");
    return text.toString();
  }
}
