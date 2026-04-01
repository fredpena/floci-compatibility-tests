package com.floci.test.tests;

import com.floci.test.FlociTestGroup;
import com.floci.test.TestContext;
import com.floci.test.TestGroup;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.*;

import java.util.List;

@FlociTestGroup
public class CloudFormationTests implements TestGroup {

    @Override
    public String name() { return "cloudformation"; }

    @Override
    public void run(TestContext ctx) {
        System.out.println("--- CloudFormation Tests ---");

        try (CloudFormationClient cfn = CloudFormationClient.builder()
                .endpointOverride(ctx.endpoint)
                .region(ctx.region)
                .credentialsProvider(ctx.credentials)
                .build()) {

            // --- Basic stack with Mappings + Fn::FindInMap ---
            String stackName = "cfn-mapping-test";
            String template = """
                    {
                      "Mappings": {
                        "EnvConfig": {
                          "us-east-1": { "BucketSuffix": "east", "QueueSuffix": "east-q" },
                          "eu-west-1": { "BucketSuffix": "west", "QueueSuffix": "west-q" }
                        },
                        "Sizing": {
                          "small":  { "Size": "1" },
                          "medium": { "Size": "5" }
                        }
                      },
                      "Parameters": {
                        "Tier": {
                          "Type": "String",
                          "Default": "small"
                        }
                      },
                      "Resources": {
                        "MappingBucket": {
                          "Type": "AWS::S3::Bucket",
                          "Properties": {
                            "BucketName": {
                              "Fn::Sub": [
                                "cfn-map-${suffix}",
                                { "suffix": { "Fn::FindInMap": ["EnvConfig", { "Ref": "AWS::Region" }, "BucketSuffix"] } }
                              ]
                            }
                          }
                        },
                        "MappingQueue": {
                          "Type": "AWS::SQS::Queue",
                          "Properties": {
                            "QueueName": {
                              "Fn::Sub": [
                                "cfn-map-${suffix}",
                                { "suffix": { "Fn::FindInMap": ["EnvConfig", { "Ref": "AWS::Region" }, "QueueSuffix"] } }
                              ]
                            }
                          }
                        },
                        "ParamMappingQueue": {
                          "Type": "AWS::SQS::Queue",
                          "Properties": {
                            "QueueName": {
                              "Fn::Sub": [
                                "cfn-tier-${size}",
                                { "size": { "Fn::FindInMap": ["Sizing", { "Ref": "Tier" }, "Size"] } }
                              ]
                            }
                          }
                        }
                      },
                      "Outputs": {
                        "BucketSuffix": {
                          "Value": { "Fn::FindInMap": ["EnvConfig", { "Ref": "AWS::Region" }, "BucketSuffix"] }
                        },
                        "TierSize": {
                          "Value": { "Fn::FindInMap": ["Sizing", { "Ref": "Tier" }, "Size"] }
                        }
                      }
                    }
                    """;

            // CreateStack
            try {
                cfn.createStack(b -> b
                        .stackName(stackName)
                        .templateBody(template)
                        .parameters(Parameter.builder().parameterKey("Tier").parameterValue("medium").build()));
                ctx.check("CFN Mappings CreateStack", true);
            } catch (Exception e) {
                ctx.check("CFN Mappings CreateStack", false, e);
                return;
            }

            // DescribeStacks — stack exists
            DescribeStacksResponse desc;
            try {
                desc = cfn.describeStacks(b -> b.stackName(stackName));
                ctx.check("CFN Mappings DescribeStacks", !desc.stacks().isEmpty());
            } catch (Exception e) {
                ctx.check("CFN Mappings DescribeStacks", false, e);
                return;
            }

            Stack stack = desc.stacks().get(0);

            // Stack status is CREATE_COMPLETE
            try {
                ctx.check("CFN Mappings stack status CREATE_COMPLETE",
                        StackStatus.CREATE_COMPLETE == stack.stackStatus());
            } catch (Exception e) {
                ctx.check("CFN Mappings stack status CREATE_COMPLETE", false, e);
            }

            // Fn::FindInMap resolved bucket name — region is us-east-1 → suffix = "east"
            try {
                boolean found = stack.outputs().stream()
                        .anyMatch(o -> "BucketSuffix".equals(o.outputKey()) && "east".equals(o.outputValue()));
                ctx.check("CFN Mappings output BucketSuffix=east", found);
            } catch (Exception e) {
                ctx.check("CFN Mappings output BucketSuffix=east", false, e);
            }

            // Fn::FindInMap with Ref to parameter — Tier=medium → Size="5"
            try {
                boolean found = stack.outputs().stream()
                        .anyMatch(o -> "TierSize".equals(o.outputKey()) && "5".equals(o.outputValue()));
                ctx.check("CFN Mappings output TierSize=5 (from param)", found);
            } catch (Exception e) {
                ctx.check("CFN Mappings output TierSize=5 (from param)", false, e);
            }

            // S3 bucket created with name resolved from mapping
            try {
                List<StackResource> resources = cfn.describeStackResources(b -> b.stackName(stackName))
                        .stackResources();
                boolean bucketFound = resources.stream()
                        .anyMatch(r -> "MappingBucket".equals(r.logicalResourceId())
                                && "cfn-map-east".equals(r.physicalResourceId()));
                ctx.check("CFN Mappings S3 bucket name=cfn-map-east", bucketFound);
            } catch (Exception e) {
                ctx.check("CFN Mappings S3 bucket name=cfn-map-east", false, e);
            }

            // SQS queue created with name resolved from mapping
            try {
                List<StackResource> resources = cfn.describeStackResources(b -> b.stackName(stackName))
                        .stackResources();
                boolean queueFound = resources.stream()
                        .anyMatch(r -> "MappingQueue".equals(r.logicalResourceId())
                                && r.physicalResourceId().contains("cfn-map-east-q"));
                ctx.check("CFN Mappings SQS queue name=cfn-map-east-q", queueFound);
            } catch (Exception e) {
                ctx.check("CFN Mappings SQS queue name=cfn-map-east-q", false, e);
            }

            // SQS queue created with name resolved from parameter-driven mapping
            try {
                List<StackResource> resources = cfn.describeStackResources(b -> b.stackName(stackName))
                        .stackResources();
                boolean queueFound = resources.stream()
                        .anyMatch(r -> "ParamMappingQueue".equals(r.logicalResourceId())
                                && r.physicalResourceId().contains("cfn-tier-5"));
                ctx.check("CFN Mappings SQS queue name=cfn-tier-5 (param-driven)", queueFound);
            } catch (Exception e) {
                ctx.check("CFN Mappings SQS queue name=cfn-tier-5 (param-driven)", false, e);
            }

            // Cleanup
            try {
                cfn.deleteStack(b -> b.stackName(stackName));
                ctx.check("CFN Mappings DeleteStack", true);
            } catch (Exception e) {
                ctx.check("CFN Mappings DeleteStack", false, e);
            }
        }
    }
}
