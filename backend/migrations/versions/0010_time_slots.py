"""Administrator-managed class time slots, with a default set of periods."""
from datetime import time
from alembic import op
import sqlalchemy as sa

revision = "0010"
down_revision = "0009"
branch_labels = None
depends_on = None

DEFAULT_SLOTS = [
    ("Period 1", time(9, 0), time(9, 50)),
    ("Period 2", time(9, 50), time(10, 40)),
    ("Period 3", time(10, 50), time(11, 40)),
    ("Period 4", time(11, 40), time(12, 30)),
    ("Period 5", time(13, 30), time(14, 20)),
    ("Period 6", time(14, 20), time(15, 10)),
    ("Period 7", time(15, 20), time(16, 10)),
]


def upgrade():
    table = op.create_table(
        "time_slots",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("label", sa.String(60), nullable=False),
        sa.Column("starts_at", sa.Time(), nullable=False),
        sa.Column("ends_at", sa.Time(), nullable=False),
        sa.Column("active", sa.Boolean(), nullable=False, server_default=sa.true()),
        sa.Column("updated_by", sa.Integer(), sa.ForeignKey("users.id"), nullable=True),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=True),
        sa.UniqueConstraint("label"),
        sa.CheckConstraint("ends_at > starts_at", name="slot_time_order"),
    )
    op.bulk_insert(table, [{"label": label, "starts_at": start, "ends_at": end, "active": True} for label, start, end in DEFAULT_SLOTS])


def downgrade():
    op.drop_table("time_slots")
