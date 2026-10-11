# ---------------------------------------------------------------------------
# Module: network — VPC baseline for MoDocument (af-south-1 design, 2 Oct 2026)
#
# Gate / control mapping:
#   A1       Cloud build-out: dedicated VPC per environment, 2 AZs.
#   SUP-S06  Database reachable ONLY from the app security group (no public
#            subnet, no 0.0.0.0/0 ingress anywhere but the edge tier).
#   A12/D3   Egress allow-list seam: app subnets route through NAT; the app
#            SG's egress is a named allow-list, and NACL stubs are in the
#            edge module for the deny-by-default posture.
#
# Topology: 2 AZs x { public (edge/ALB), private-app (ECS), private-db (RDS) }.
# NOT APPLIED anywhere yet — `terraform validate` passes (see the README
# status); plan/apply pending.
# ---------------------------------------------------------------------------

locals {
  az_names = slice(data.aws_availability_zones.available.names, 0, 2)
}

data "aws_availability_zones" "available" {
  state = "available"
}

resource "aws_vpc" "this" {
  cidr_block           = var.vpc_cidr
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = merge(var.tags, { Name = "${var.name}-vpc" })
}

# --- Subnets ---------------------------------------------------------------

resource "aws_subnet" "public" {
  count                   = 2
  vpc_id                  = aws_vpc.this.id
  availability_zone       = local.az_names[count.index]
  cidr_block              = cidrsubnet(var.vpc_cidr, 4, count.index)
  map_public_ip_on_launch = false # edge is the ALB; instances never get public IPs

  tags = merge(var.tags, { Name = "${var.name}-public-${local.az_names[count.index]}", Tier = "public" })
}

resource "aws_subnet" "private_app" {
  count             = 2
  vpc_id            = aws_vpc.this.id
  availability_zone = local.az_names[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, count.index + 4)

  tags = merge(var.tags, { Name = "${var.name}-app-${local.az_names[count.index]}", Tier = "private-app" })
}

resource "aws_subnet" "private_db" {
  count             = 2
  vpc_id            = aws_vpc.this.id
  availability_zone = local.az_names[count.index]
  cidr_block        = cidrsubnet(var.vpc_cidr, 4, count.index + 8)

  tags = merge(var.tags, { Name = "${var.name}-db-${local.az_names[count.index]}", Tier = "private-db" })
}

# --- Routing ---------------------------------------------------------------

resource "aws_internet_gateway" "this" {
  vpc_id = aws_vpc.this.id
  tags   = merge(var.tags, { Name = "${var.name}-igw" })
}

resource "aws_eip" "nat" {
  count  = 2
  domain = "vpc"
  tags   = merge(var.tags, { Name = "${var.name}-nat-${count.index}" })
}

resource "aws_nat_gateway" "this" {
  count         = 2
  allocation_id = aws_eip.nat[count.index].id
  subnet_id     = aws_subnet.public[count.index].id
  tags          = merge(var.tags, { Name = "${var.name}-nat-${local.az_names[count.index]}" })

  depends_on = [aws_internet_gateway.this]
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.this.id
  tags   = merge(var.tags, { Name = "${var.name}-rt-public" })
}

resource "aws_route" "public_internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.this.id
}

resource "aws_route_table_association" "public" {
  count          = 2
  subnet_id      = aws_subnet.public[count.index].id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "private_app" {
  count  = 2
  vpc_id = aws_vpc.this.id
  tags   = merge(var.tags, { Name = "${var.name}-rt-app-${local.az_names[count.index]}" })
}

resource "aws_route" "app_nat" {
  count                  = 2
  route_table_id         = aws_route_table.private_app[count.index].id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.this[count.index].id
}

resource "aws_route_table_association" "private_app" {
  count          = 2
  subnet_id      = aws_subnet.private_app[count.index].id
  route_table_id = aws_route_table.private_app[count.index].id
}

# DB subnets get NO internet route at all (not even NAT): RDS needs none.
resource "aws_route_table" "private_db" {
  vpc_id = aws_vpc.this.id
  tags   = merge(var.tags, { Name = "${var.name}-rt-db" })
}

resource "aws_route_table_association" "private_db" {
  count          = 2
  subnet_id      = aws_subnet.private_db[count.index].id
  route_table_id = aws_route_table.private_db.id
}

# --- Security groups --------------------------------------------------------

# ALB: the only thing that accepts traffic from the world — and in the
# production env the edge module further restricts this to the CloudFront
# managed prefix list (origin cannot be reached around the WAF, gate A12/D3).
resource "aws_security_group" "alb" {
  name_prefix = "${var.name}-alb-"
  description = "Edge ALB: HTTPS in from CloudFront (or the world in staging)"
  vpc_id      = aws_vpc.this.id

  tags = merge(var.tags, { Name = "${var.name}-alb" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group" "app" {
  name_prefix = "${var.name}-app-"
  description = "ECS app tasks: traffic only from the ALB SG"
  vpc_id      = aws_vpc.this.id

  tags = merge(var.tags, { Name = "${var.name}-app" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group" "db" {
  name_prefix = "${var.name}-db-"
  description = "RDS PostgreSQL: 5432 only from the app SG (SUP-S06)"
  vpc_id      = aws_vpc.this.id

  tags = merge(var.tags, { Name = "${var.name}-db" })

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_security_group_rule" "app_from_alb" {
  type                     = "ingress"
  security_group_id        = aws_security_group.app.id
  source_security_group_id = aws_security_group.alb.id
  protocol                 = "tcp"
  from_port                = var.app_port
  to_port                  = var.app_port
  description              = "nginx/app port from the ALB only"
}

# SUP-S06: the database answers the app security group and nothing else.
resource "aws_security_group_rule" "db_from_app" {
  type                     = "ingress"
  security_group_id        = aws_security_group.db.id
  source_security_group_id = aws_security_group.app.id
  protocol                 = "tcp"
  from_port                = 5432
  to_port                  = 5432
  description              = "PostgreSQL from app tasks only (SUP-S06)"
}

# A12/D3 egress allow-list seam: the app may reach HTTPS (LLM endpoint, OIDC
# issuer, AWS APIs via endpoints/NAT) and the DB port inside the VPC. Tighten
# the HTTPS rule to explicit prefix lists / proxy CIDRs when the egress
# allow-list (gate D3) is enforced organisationally — this rule is the seam.
resource "aws_security_group_rule" "app_egress_https" {
  type              = "egress"
  security_group_id = aws_security_group.app.id
  protocol          = "tcp"
  from_port         = 443
  to_port           = 443
  cidr_blocks       = var.app_egress_https_cidrs
  description       = "HTTPS egress allow-list (D3 seam: narrow these CIDRs)"
}

resource "aws_security_group_rule" "app_egress_db" {
  type                     = "egress"
  security_group_id        = aws_security_group.app.id
  protocol                 = "tcp"
  from_port                = 5432
  to_port                  = 5432
  source_security_group_id = aws_security_group.db.id
  description              = "PostgreSQL to the DB SG"
}

resource "aws_security_group_rule" "alb_egress_app" {
  type                     = "egress"
  security_group_id        = aws_security_group.alb.id
  protocol                 = "tcp"
  from_port                = var.app_port
  to_port                  = var.app_port
  source_security_group_id = aws_security_group.app.id
  description              = "ALB forwards to app tasks only"
}

# ws26 bank integration hub, DIRECT mode: one egress rule per DB vendor port
# and SFTP, all to var.bank_egress_cidrs — and none at all while that list
# is empty (the default). AGENT-mode connections need nothing here: the
# on-prem agent dials OUT to MoDocument over HTTPS.
locals {
  bank_egress_ports = {
    sqlserver = 1433
    oracle    = 1521
    mariadb   = 3306
    postgres  = 5432
    sftp      = 22
  }
}

resource "aws_security_group_rule" "app_egress_bank" {
  for_each = length(var.bank_egress_cidrs) > 0 ? local.bank_egress_ports : {}

  type              = "egress"
  security_group_id = aws_security_group.app.id
  protocol          = "tcp"
  from_port         = each.value
  to_port           = each.value
  cidr_blocks       = var.bank_egress_cidrs
  description       = "Bank integration (${each.key}) to approved bank networks only"
}

# The DB SG has NO egress rules at all: RDS initiates nothing.

# --- VPC endpoints ----------------------------------------------------------
# NOTE on VPC endpoints: for a full D3 (egress allow-list) posture, add
# interface endpoints for ssm, ssmmessages, ec2messages, secretsmanager,
# logs, ecr.api, ecr.dkr and a gateway endpoint for s3, then remove the
# blanket 443 NAT path for AWS traffic. The S3 gateway endpoint below is
# the first (free) step; the interface endpoints are stubbed behind
# var.enable_interface_endpoints to keep staging cheap.

resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.this.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids = concat(
    [for rt in aws_route_table.private_app : rt.id],
    [aws_route_table.private_db.id],
  )

  tags = merge(var.tags, { Name = "${var.name}-s3-endpoint" })
}

resource "aws_vpc_endpoint" "interface" {
  for_each = var.enable_interface_endpoints ? toset([
    "ssm", "ssmmessages", "ec2messages", "secretsmanager", "logs",
    "ecr.api", "ecr.dkr",
  ]) : toset([])

  vpc_id              = aws_vpc.this.id
  service_name        = "com.amazonaws.${var.region}.${each.value}"
  vpc_endpoint_type   = "Interface"
  subnet_ids          = [for s in aws_subnet.private_app : s.id]
  security_group_ids  = [aws_security_group.app.id]
  private_dns_enabled = true

  tags = merge(var.tags, { Name = "${var.name}-${each.value}-endpoint" })
}

# VPC flow logs → CloudWatch (detective input for the security-monitoring
# module; PRIV-003 ransomware/beaconing detection feeds on these).
resource "aws_cloudwatch_log_group" "flow_logs" {
  name              = "/vpc/${var.name}/flow-logs"
  retention_in_days = 400

  tags = var.tags
}

resource "aws_iam_role" "flow_logs" {
  name_prefix = "${var.name}-flowlogs-"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "vpc-flow-logs.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })

  tags = var.tags
}

resource "aws_iam_role_policy" "flow_logs" {
  name_prefix = "${var.name}-flowlogs-"
  role        = aws_iam_role.flow_logs.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "logs:CreateLogStream",
        "logs:PutLogEvents",
        "logs:DescribeLogGroups",
        "logs:DescribeLogStreams",
      ]
      Resource = "${aws_cloudwatch_log_group.flow_logs.arn}:*"
    }]
  })
}

resource "aws_flow_log" "this" {
  vpc_id                   = aws_vpc.this.id
  traffic_type             = "ALL"
  log_destination_type     = "cloud-watch-logs"
  log_destination          = aws_cloudwatch_log_group.flow_logs.arn
  iam_role_arn             = aws_iam_role.flow_logs.arn
  max_aggregation_interval = 60

  tags = merge(var.tags, { Name = "${var.name}-flow-logs" })
}
