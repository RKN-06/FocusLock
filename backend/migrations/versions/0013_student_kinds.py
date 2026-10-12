"""Registration type (regular, reregistered, other) on enrolments and group members."""
from alembic import op
import sqlalchemy as sa

revision = "0013"
down_revision = "0012"
branch_labels = None
depends_on = None


def upgrade():
    for table in ("enrollments", "group_members"):
        op.add_column(table, sa.Column("kind", sa.String(16), nullable=False, server_default="regular"))


def downgrade():
    for table in ("group_members", "enrollments"):
        with op.batch_alter_table(table) as batch:
            batch.drop_column("kind")
