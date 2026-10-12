"""Student groups (cohorts), group membership, course-to-group links and the enrolment source."""
from alembic import op
import sqlalchemy as sa

revision = "0011"
down_revision = "0010"
branch_labels = None
depends_on = None


def upgrade():
    op.create_table(
        "student_groups",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("name", sa.String(80), nullable=False),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.UniqueConstraint("name"),
    )
    op.create_table(
        "group_members",
        sa.Column("group_id", sa.Integer(), sa.ForeignKey("student_groups.id"), primary_key=True),
        sa.Column("student_id", sa.Integer(), sa.ForeignKey("users.id"), primary_key=True),
    )
    op.create_table(
        "course_groups",
        sa.Column("course_id", sa.Integer(), sa.ForeignKey("course_offerings.id"), primary_key=True),
        sa.Column("group_id", sa.Integer(), sa.ForeignKey("student_groups.id"), primary_key=True),
    )
    with op.batch_alter_table("enrollments") as batch:
        batch.add_column(sa.Column("source", sa.String(8), nullable=False, server_default="manual"))


def downgrade():
    with op.batch_alter_table("enrollments") as batch:
        batch.drop_column("source")
    op.drop_table("course_groups")
    op.drop_table("group_members")
    op.drop_table("student_groups")
