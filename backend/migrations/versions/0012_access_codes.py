"""Teacher-set backup class codes (enter and exit) and the 'code' attendance method."""
from alembic import op
import sqlalchemy as sa

revision = "0012"
down_revision = "0011"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "session_codes",
        sa.Column("session_id", sa.Integer(), sa.ForeignKey("sessions.id"), primary_key=True),
        sa.Column("enter_digest", sa.String(64), nullable=True),
        sa.Column("exit_digest", sa.String(64), nullable=True),
        sa.Column("updated_by", sa.Integer(), sa.ForeignKey("users.id"), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
    )
    with op.batch_alter_table("attendance") as batch:
        batch.drop_constraint("attendance_method", type_="check")
        batch.create_check_constraint("attendance_method", "method IN ('teacher_confirmed', 'qr', 'code')")


def downgrade():
    op.execute("UPDATE attendance SET method = 'teacher_confirmed' WHERE method = 'code'")
    with op.batch_alter_table("attendance") as batch:
        batch.drop_constraint("attendance_method", type_="check")
        batch.create_check_constraint("attendance_method", "method IN ('teacher_confirmed', 'qr')")
    op.drop_table("session_codes")
